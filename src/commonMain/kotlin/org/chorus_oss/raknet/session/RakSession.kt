package org.chorus_oss.raknet.session

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.io.*
import kotlinx.io.Buffer
import kotlinx.io.bytestring.ByteString
import org.chorus_oss.raknet.config.RakSessionConfig
import org.chorus_oss.raknet.protocol.packets.*
import org.chorus_oss.raknet.protocol.types.Frame
import org.chorus_oss.raknet.types.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalAtomicApi::class, ExperimentalUnsignedTypes::class)
class RakSession(
    context: CoroutineContext,
    private val outbound: SendChannel<Datagram>,
    val address: InetSocketAddress,
    val guid: ULong,
    val mtu: UShort,
    config: RakSessionConfig.() -> Unit = {}
) : CoroutineScope {
    override val coroutineContext: CoroutineContext = context

    val config = RakSessionConfig().apply(config)

    internal var state: RakSessionState = RakSessionState.Connecting
    private var lastUpdate: Instant = Clock.System.now()

    private val queued: Channel<Datagram> = Channel(Channel.UNLIMITED)

    private val receivedFrameSequences = mutableSetOf<UInt>()
    private val lostFrameSequences = mutableSetOf<UInt>()
    private val inputHighestSequenceIndex = UIntArray(32)
    private val fragmentsQueue = mutableMapOf<UShort, MutableMap<UInt, Frame>>()

    private val inputOrderIndex = UIntArray(32)
    private val inputOrderingQueue = mutableMapOf<UByte, MutableMap<UInt, Frame>>()

    private var lastInputSequence: UInt? = null

    private val outputOrderIndex = UIntArray(32)
    private val outputSequenceIndex = UIntArray(32)

    private val outFrames = Channel<Frame>(capacity = Channel.UNLIMITED)

    private val outCache = mutableMapOf<UInt, List<Frame>>()

    private val outSequenceID: AtomicInt = AtomicInt(0)
    private val outSplitID: AtomicInt = AtomicInt(0)
    private val outReliableIndex: AtomicInt = AtomicInt(0)

    private val out = Channel<Pair<Frame, RakPriority>>(capacity = Channel.UNLIMITED)

    val inbound = Channel<Source>(capacity = Channel.UNLIMITED)

    var onPacket: (Source) -> Unit = {}
    var onError: (Error) -> Unit = {}

    fun onPacket(fn: (Source) -> Unit): RakSession {
        this.onPacket = fn
        return this
    }

    fun onError(fn: (Error) -> Unit): RakSession {
        this.onError = fn
        return this
    }

    private val update: Job = launch {
        var last = Clock.System.now()
        while (isActive) {
            generateSequence { out.tryReceive().getOrNull() }.forEach { sendFrame(it.first, it.second) }
            generateSequence { inbound.tryReceive().getOrNull() }.forEach(::inbound)

            val now = Clock.System.now()
            if (now - last >= 10.milliseconds) {
                tick()
                last = now
            }
        }
    }

    fun flush() {
        while (true) {
            outbound.trySend(queued.tryReceive().getOrNull() ?: break)
        }
    }

    private fun tick() {
        if (state == RakSessionState.Disconnecting || state == RakSessionState.Disconnected) {
            return
        }

        if (lastUpdate.plus(15000.milliseconds) < Clock.System.now()) {
            log.warn { "Detected stale connection from $address, disconnecting..." }

            return disconnect(send = true, connected = true)
        }


        if (receivedFrameSequences.isNotEmpty()) {
            val ack = Ack(
                sequences = receivedFrameSequences.toList(),
            )

            receivedFrameSequences.clear()

            queued.trySend(
                Datagram(
                    packet = Buffer().also {
                        Ack.serialize(ack, it)
                    },
                    address = address
                )
            )
        }

        if (lostFrameSequences.isNotEmpty()) {
            val nack = NAck(
                sequences = lostFrameSequences.toList(),
            )

            lostFrameSequences.clear()

            queued.trySend(
                Datagram(
                    packet = Buffer().also {
                        NAck.serialize(nack, it)
                    },
                    address = address
                )
            )
        }

        sendQueue()
        flush()
    }

    internal fun disconnect(send: Boolean, connected: Boolean) {
        state = RakSessionState.Disconnecting

        if (send) {
            val disconnect = Disconnect()

            val frame = Frame(
                reliability = RakReliability.ReliableOrdered,
                orderChannel = 0u,
                payload = Buffer().also {
                    Disconnect.serialize(disconnect, it)
                }.readByteString()
            )

            sendFrame(frame, RakPriority.Immediate)
        }

        if (connected) config.onDisconnect(this)

        state = RakSessionState.Disconnected

        update.cancel()
    }

    private fun inbound(stream: Source) {
        lastUpdate = Clock.System.now()

        when (val header = stream.peek().readUByte() and 0xF0.toUByte()) {
            RakPacketID.ACK -> handleACK(stream)
            RakPacketID.NACK -> handleNACK(stream)
            RakPacketID.FRAME_SET -> handleFrameSet(stream)

            else -> {
                val id = header.toString(16).padStart(2, '0').uppercase()

                log.warn { "Received unknown online packet \"0x$id\" from $address" }

                onError(Error("Received unknown online packet \"0x$id\" from $address"))
            }
        }
    }

    private fun handleACK(stream: Source) {
        val ack = Ack.deserialize(stream)

        for (sequence in ack.sequences) {
            if (!outCache.contains(sequence)) {
                log.debug { "Received ack for unknown sequence $sequence from $address" }
            }

            outCache.remove(sequence)
        }
    }

    private fun handleNACK(stream: Source) {
        val nack = NAck.deserialize(stream)

        for (sequence in nack.sequences) {
            val frames = outCache[sequence] ?: emptyList()
            for (frame in frames) {
                queueFrame(frame, RakPriority.Immediate)
            }
        }
    }

    private fun handleFrameSet(stream: Source) {
        val frameSet = FrameSet.deserialize(stream)

        if (receivedFrameSequences.contains(frameSet.sequence)) {
            log.warn { "Received duplicate frameset ${frameSet.sequence} from $address" }

            return onError(Error("Received duplicate frameset ${frameSet.sequence} from $address"))
        }

        receivedFrameSequences.add(frameSet.sequence)

        lastInputSequence?.let {
            if (frameSet.sequence <= it) {
                log.warn { "Received out of order frameset ${frameSet.sequence} from $address. expected ${it + 1u}" }

                return onError(Error("Received out of order frameset ${frameSet.sequence} from $address"))
            }
        }

        lostFrameSequences.remove(frameSet.sequence)

        lastInputSequence?.let {
            if (frameSet.sequence - it > 1u) {
                for (i in it + 1u until frameSet.sequence) {
                    lostFrameSequences.add(i)
                }
            }
        }

        lastInputSequence = frameSet.sequence

        for (frame in frameSet.frames) {
            handleFrame(frame)
        }
    }

    private fun handleFrame(frame: Frame) {
        if (frame.isSplit) return handleFragment(frame)

        if (frame.reliability.isSequenced) {
            if (
                frame.sequenceIndex < (inputHighestSequenceIndex[frame.orderChannel.toInt()]) ||
                frame.orderIndex < (inputOrderIndex[frame.orderChannel.toInt()])
            ) {
                log.warn { "Received out of order frame ${frame.sequenceIndex} from $address" }

                return onError(Error("Received out of order frame ${frame.sequenceIndex} from $address"))
            }

            inputHighestSequenceIndex[frame.orderChannel.toInt()] = frame.sequenceIndex + 1u

            return config.onInbound(this, Buffer().apply { write(frame.payload) })
        } else if (frame.reliability.isOrdered) {
            if (frame.orderIndex == inputOrderIndex[frame.orderChannel.toInt()]) {
                inputHighestSequenceIndex[frame.orderChannel.toInt()] = 0u
                inputOrderIndex[frame.orderChannel.toInt()] = frame.orderIndex + 1u

                config.onInbound(this, Buffer().apply { write(frame.payload) })

                var index = inputOrderIndex[frame.orderChannel.toInt()]
                val outOfOrderQueue = inputOrderingQueue.getOrPut(frame.orderChannel) { mutableMapOf() }

                while (true) {
                    val outOfOrderFrame = outOfOrderQueue[index] ?: break

                    config.onInbound(this, Buffer().apply { write(outOfOrderFrame.payload) })
                    outOfOrderQueue.remove(index)

                    index++
                }

                inputOrderingQueue[frame.orderChannel] = outOfOrderQueue
                inputOrderIndex[frame.orderChannel.toInt()] = index
            } else if (frame.orderIndex > inputOrderIndex[frame.orderChannel.toInt()]) {
                val unordered = inputOrderingQueue.getOrPut(frame.orderChannel) { mutableMapOf() }

                unordered[frame.orderIndex] = frame
            }
        } else {
            return config.onInbound(this, Buffer().apply { write(frame.payload) })
        }
    }

    private fun handleFragment(frame: Frame) {
        val fragment = fragmentsQueue.getOrPut(frame.splitID) { mutableMapOf() }

        fragment[frame.splitIndex] = frame

        if (fragment.size.toUInt() == frame.splitSize) {
            val stream = Buffer()

            for (i in 0u until frame.splitSize) {
                val f = fragment[i] ?: return
                stream.write(f.payload)
            }

            val nFrame = frame.copy(
                payload = stream.readByteString(),
                splitSize = 0u,
                splitID = 0u,
                splitIndex = 0u,
            )

            fragmentsQueue.remove(frame.splitID)

            return handleFrame(nFrame)
        }
    }

    private fun sendFrame(frame: Frame, priority: RakPriority) {
        val orderChannel = frame.orderChannel.toInt()

        if (frame.reliability.isSequenced) {
            frame.orderIndex = outputOrderIndex[orderChannel]
            frame.sequenceIndex = outputSequenceIndex[orderChannel]++
        } else if (frame.reliability.isOrderExclusive) {
            frame.orderIndex = outputOrderIndex[orderChannel]++
            outputSequenceIndex[orderChannel] = 0u
        }

        val maxSize = (mtu - RakConstants.DGRAM_MTU_OVERHEAD).toInt()
        val splitSize = (frame.payload.size + maxSize - 1) / maxSize

        frame.reliableIndex = outReliableIndex.fetchAndIncrement().toUInt()

        if (frame.payload.size > maxSize.toLong()) {
            val splitID = outSplitID.fetchAndIncrement().toUShort()

            for (i in 0 until splitSize) {
                val start = i * maxSize
                val end = minOf(start + maxSize, frame.payload.size)

                val nFrame = frame.copy(
                    payload = frame.payload.substring(start, end),
                    splitIndex = i.toUInt(),
                    splitID = splitID,
                    splitSize = splitSize.toUInt()
                )

                queueFrame(nFrame, priority)
            }
        } else {
            queueFrame(frame, priority)
        }
    }

    private fun queueFrame(frame: Frame, priority: RakPriority) {
        when (priority) {
            RakPriority.Immediate -> sendImmediate(frame)
            else -> outFrames.trySend(frame)
        }
    }

    private fun sendQueue() {
        val frames = generateSequence { outFrames.tryReceive().getOrNull() }

        val max = (mtu - RakConstants.DGRAM_MTU_OVERHEAD).toLong()

        val batch = mutableListOf<Frame>()
        var size = RakConstants.DGRAM_HEADER_SIZE.toLong()
        for (frame in frames) {
            if (size + frame.byteLength > max) {
                sendFrames(batch)
                batch.clear()
                size = RakConstants.DGRAM_HEADER_SIZE.toLong()
            }

            size += frame.byteLength
            batch.add(frame)
        }

        if (batch.isNotEmpty()) sendFrames(batch)
    }

    private fun sendFrames(frames: List<Frame>) {
        if (frames.isEmpty()) return

        val set = FrameSet(
            sequence = outSequenceID.fetchAndIncrement().toUInt(),
            frames = frames.toList(),
        )

        outCache[set.sequence] = set.frames.toList()

        queued.trySend(
            Datagram(
                packet = Buffer().also {
                    FrameSet.serialize(set, it)
                },
                address = address
            )
        )
    }

    private fun sendImmediate(frame: Frame) {
        val set = FrameSet(
            sequence = outSequenceID.fetchAndIncrement().toUInt(),
            frames = listOf(frame),
        )

        outCache[set.sequence] = set.frames.toList()

        outbound.trySend(
            Datagram(
                packet = Buffer().also {
                    FrameSet.serialize(set, it)
                },
                address = address
            )
        )
    }

    fun send(packet: ByteString, reliability: RakReliability, priority: RakPriority) {
        out.trySend(
            Frame(
                payload = packet,
                reliability = reliability,
            ) to priority
        )
    }

    companion object {
        internal val log = KotlinLogging.logger {}
    }
}