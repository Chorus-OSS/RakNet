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

    internal val config = RakSessionConfig().apply(config)

    internal var state: RakSessionState = RakSessionState.Connecting

    private var lastUpdate: Instant = Clock.System.now()
    private var lastTick: Instant = Clock.System.now()

    private var currPing: Instant = Instant.DISTANT_PAST
    private var lastPing: Instant = Instant.DISTANT_PAST
    private var lastPong: Instant = Instant.DISTANT_PAST

    val ping: Long
        get() = lastPing.toEpochMilliseconds() - lastPong.toEpochMilliseconds()

    val isConnected: Boolean
        get() = state == RakSessionState.Connected

    val isDisconnected: Boolean
        get() = state == RakSessionState.Disconnected

    private val slidingWindow: RakSlidingWindow = RakSlidingWindow(mtu)

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

    private val outFrames = ArrayDeque<Frame>()

    private val outCache = mutableMapOf<UInt, FrameSet>()

    private var outSequenceID: UInt = 0u
    private var outSplitID: UShort = 0u
    private var outReliableIndex: UInt = 0u

    private val out = Channel<Pair<Frame, RakPriority>>(capacity = Channel.UNLIMITED)

    val inbound = Channel<Source>(capacity = Channel.UNLIMITED)

    var onPacket: (Source) -> Unit = {}

    fun onPacket(fn: (Source) -> Unit): RakSession {
        this.onPacket = fn
        return this
    }

    private val update: Job = launch {
        while (isActive) {
            generateSequence { out.tryReceive().getOrNull() }.forEach { sendFrame(it.first, it.second) }
            generateSequence { inbound.tryReceive().getOrNull() }.forEach(::inbound)

            val now = Clock.System.now()
            if (lastTick + 10.milliseconds <= now) {
                tick()
                lastTick = now
            }

            if (currPing + 2000.milliseconds <= now) {
                val ping = ConnectedPing(
                    timestamp = now.toEpochMilliseconds().toULong(),
                )

                send(
                    Buffer().apply { ConnectedPing.serialize(ping, this) }.readByteString(),
                    RakReliability.Unreliable,
                    RakPriority.Immediate,
                )

                currPing = now
            }

            yield()
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

        sendStale()
        sendQueue()
        flush()
    }

    fun disconnect() {
        val connected = state == RakSessionState.Connected
        disconnect(connected, connected)
    }

    private fun disconnect(send: Boolean, connected: Boolean) {
        if (state == RakSessionState.Disconnecting || state == RakSessionState.Disconnected) return

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
            }
        }
    }

    private fun handlePacket(stream: Source) {
        when (stream.peek().readUByte()) {
            RakPacketID.CONNECTED_PING -> handleConnectedPing(stream)
            RakPacketID.CONNECTED_PONG -> handleConnectedPong(stream)
            RakPacketID.DISCONNECT -> handleDisconnect(stream)
            else -> config.onInbound(this, stream)
        }
    }

    private fun handleACK(stream: Source) {
        val ack = Ack.deserialize(stream)

        val now = Clock.System.now()

        for (sequence in ack.sequences) {
            outCache.remove(sequence)?.let { set ->
                lastInputSequence?.let {
                    slidingWindow.acked(now, set, it)
                }
            }
        }
    }

    private fun handleNACK(stream: Source) {
        val nack = NAck.deserialize(stream)

        for (sequence in nack.sequences) {
            outCache.remove(sequence)?.let {
                sendImmediate(it.frames)
                slidingWindow.nacked()
            }
        }
    }

    private fun handleFrameSet(stream: Source) {
        val frameSet = FrameSet.deserialize(stream)

        if (receivedFrameSequences.contains(frameSet.sequence)) {
            log.warn { "Received duplicate frameset ${frameSet.sequence} from $address" }
        }

        receivedFrameSequences.add(frameSet.sequence)

        lastInputSequence?.let {
            if (frameSet.sequence <= it) {
                log.warn { "Received out of order frameset ${frameSet.sequence} from $address. expected ${it + 1u}" }
            }
        }

        lostFrameSequences.remove(frameSet.sequence)

        lastInputSequence?.let {
            if (frameSet.sequence - it > 1u) {
                lostFrameSequences.addAll(it + 1u until frameSet.sequence)
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
            }

            inputHighestSequenceIndex[frame.orderChannel.toInt()] = frame.sequenceIndex + 1u

            return handlePacket(Buffer().apply { write(frame.payload) })
        } else if (frame.reliability.isOrdered) {
            if (frame.orderIndex == inputOrderIndex[frame.orderChannel.toInt()]) {
                inputHighestSequenceIndex[frame.orderChannel.toInt()] = 0u
                inputOrderIndex[frame.orderChannel.toInt()] = frame.orderIndex + 1u

                handlePacket(Buffer().apply { write(frame.payload) })

                var index = inputOrderIndex[frame.orderChannel.toInt()]
                val outOfOrderQueue = inputOrderingQueue.getOrPut(frame.orderChannel) { mutableMapOf() }

                while (true) {
                    val outOfOrderFrame = outOfOrderQueue[index] ?: break

                    handlePacket(Buffer().apply { write(outOfOrderFrame.payload) })
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
            return handlePacket(Buffer().apply { write(frame.payload) })
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

        frame.reliableIndex = outReliableIndex++

        if (frame.size > maxSize) {
            val splitID = outSplitID++

            val splitFrames = mutableListOf<Frame>()
            for (i in 0 until splitSize) {
                val start = i * maxSize
                val end = minOf(start + maxSize, frame.payload.size)

                val nFrame = frame.copy(
                    payload = frame.payload.substring(start, end),
                    splitIndex = i.toUInt(),
                    splitID = splitID,
                    splitSize = splitSize.toUInt()
                )

                splitFrames.add(nFrame)
            }

            queueFrames(splitFrames, priority)
        } else {
            queueFrames(listOf(frame), priority)
        }
    }

    private fun queueFrames(frame: List<Frame>, priority: RakPriority) {
        when (priority) {
            RakPriority.Immediate -> sendImmediate(frame)
            else -> outFrames.addAll(frame)
        }
    }

    private fun sendQueue() {
        var bandwidth = slidingWindow.transmissionBandwidth

        val frames = generateSequence {
            outFrames.firstOrNull()?.let {
                if (bandwidth < it.size) null
                else {
                    @Suppress("AssignedValueIsNeverRead")
                    bandwidth -= it.size
                    outFrames.removeFirstOrNull()
                }
            }
        }.toList()

        val sets = createFrameSets(frames)
        for (set in sets) {
            sendFrameSet(set, queued)
        }
    }

    private fun sendStale() {
        if (outCache.isEmpty()) return

        val now = Clock.System.now()

        var resent = false
        var bandwidth = slidingWindow.retransmissionBandwidth

        for (set in outCache.values.toList()) {
            if (set.resend <= now) {
                if (bandwidth < set.size) break
                bandwidth -= set.size
                resent = true

                sendFrameSet(set, queued)
                outCache.remove(set.sequence)
            }
        }

        if (resent) {
            slidingWindow.resent(outSequenceID)
        }
    }

    private fun createFrameSets(frames: List<Frame>): List<FrameSet> {
        val sets = mutableListOf<FrameSet>()

        val max = (mtu - RakConstants.DGRAM_HEADER_SIZE).toLong()

        val batch = mutableListOf<Frame>()
        var size = RakConstants.DGRAM_HEADER_SIZE.toLong()
        for (frame in frames) {
            if (frame.size > max) {
                throw IllegalArgumentException("Frame too large for FrameSet, size: ${frame.size}, max size: $max")
            }

            if (size + frame.size > max) {
                sets.add(
                    FrameSet(
                        sequence = outSequenceID++,
                        frames = batch.toList(),
                    )
                )
                batch.clear()
                size = RakConstants.DGRAM_HEADER_SIZE.toLong()
            }

            size += frame.size
            batch.add(frame)
        }

        if (batch.isNotEmpty()) sets.add(
            FrameSet(
                sequence = outSequenceID++,
                frames = batch.toList(),
            )
        )

        return sets
    }

    private fun sendFrameSet(set: FrameSet, channel: SendChannel<Datagram> = queued) {
        if (set.frames.any { it.reliability.isReliable }) {
            set.resend = Clock.System.now() + slidingWindow.retransmissionTimeOut
            if (!outCache.contains(set.sequence)) {
                slidingWindow.sent(set)
            }
            outCache[set.sequence] = set
        }

        channel.trySend(
            Datagram(
                packet = Buffer().also {
                    FrameSet.serialize(set, it)
                },
                address = address
            )
        )
    }

    private fun sendImmediate(frames: List<Frame>) {
        val sets = createFrameSets(frames)

        for (set in sets) {
            sendFrameSet(set, outbound)
        }
    }

    fun send(packet: ByteString, reliability: RakReliability, priority: RakPriority) {
        out.trySend(
            Frame(
                payload = packet,
                reliability = reliability,
            ) to priority
        )
    }

    private fun handleDisconnect(stream: Source) {
        Disconnect.deserialize(stream)

        log.trace { "RakSession closed by $address" }
        disconnect(send = false, connected = true)
    }

    private fun handleConnectedPong(stream: Source) {
        val pong = ConnectedPong.deserialize(stream)

        val ping = Instant.fromEpochMilliseconds(pong.timestamp.toLong())
        if (this.currPing == ping) {
            this.lastPing = this.currPing
            this.lastPong = Clock.System.now()
        }
    }

    private fun handleConnectedPing(stream: Source) {
        val ping = ConnectedPing.deserialize(stream)

        val pong = ConnectedPong(
            pingTimestamp = ping.timestamp,
            timestamp = Clock.System.now().toEpochMilliseconds().toULong()
        )

        send(
            Buffer().apply { ConnectedPong.serialize(pong, this) }.readByteString(),
            RakReliability.Unreliable,
            RakPriority.Immediate,
        )
    }

    companion object {
        internal val log = KotlinLogging.logger {}
    }
}