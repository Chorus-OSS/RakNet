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
import org.chorus_oss.raknet.utils.hasFlag
import org.chorus_oss.raknet.utils.overhead
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalAtomicApi::class, ExperimentalUnsignedTypes::class)
class RakSession(
    context: CoroutineContext,
    private val outbound: SendChannel<Datagram>,
    val address: InetSocketAddress,
    val guid: ULong,
    mtu: UShort,
    config: RakSessionConfig.() -> Unit = {}
) : CoroutineScope {
    override val coroutineContext: CoroutineContext = context

    val mtu: UShort = (mtu - RakConstants.UDP_HEADER_SIZE - address.overhead).toUShort()

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

    var onTick: () -> Unit = {}
    var onPacket: (Source) -> Unit = {}

    fun onTick(fn: () -> Unit): RakSession {
        this.onTick = fn
        return this
    }

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

            if (lastUpdate + 15000.milliseconds <= now) {
                log.debug { "Detected stale connection from $address, disconnecting..." }

                disconnect(send = true, connected = true)
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

        onTick.invoke()

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

        val flags = stream.peek().readUByte()
        if (flags hasFlag RakFlags.VALID) {
            when {
                flags hasFlag RakFlags.ACK -> handleACK(stream)
                flags hasFlag RakFlags.NACK -> handleNACK(stream)
                else -> handleFrameSet(stream)
            }
        } else log.debug { "Received unknown online packet ${flags.toHexString(HexFormat.UpperCase)} from $address" }
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
            log.debug { "Received duplicate frameset ${frameSet.sequence} from $address" }
        }

        receivedFrameSequences.add(frameSet.sequence)

        lastInputSequence?.let {
            if (frameSet.sequence <= it) {
                log.debug { "Received out of order frameset ${frameSet.sequence} from $address. expected ${it + 1u}" }
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
                log.debug { "Received out of order frame ${frame.sequenceIndex} from $address" }
            }

            inputHighestSequenceIndex[frame.orderChannel.toInt()] = frame.sequenceIndex + 1u

            return handlePacket(Buffer().apply { write(frame.payload) })
        } else if (frame.reliability.isOrdered || frame.reliability.isSequenced) {
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
        val maxSize = (mtu - RakConstants.DGRAM_MTU_OVERHEAD).toInt()

        val orderChannel = frame.orderChannel
        var reliability: RakReliability = frame.reliability
        var splitID: UShort = 0u

        val payloads = if (frame.size > maxSize) {
            reliability = when (reliability) {
                RakReliability.Unreliable -> RakReliability.Reliable
                RakReliability.UnreliableSequenced -> RakReliability.ReliableSequenced
                RakReliability.UnreliableWithAckReceipt -> RakReliability.ReliableWithAckReceipt
                else -> reliability
            }
            splitID = outSplitID++

            val splitSize = (frame.payload.size + maxSize - 1) / maxSize

            (0 until splitSize).map {
                val start = it * maxSize
                val end = minOf(start + maxSize, frame.payload.size)

                frame.payload.substring(start, end)
            }
        } else {
            listOf(frame.payload)
        }

        var orderIndex = 0u
        var sequenceIndex = 0u
        if (frame.reliability.isSequenced) {
            orderIndex = outputOrderIndex[orderChannel.toInt()]
            sequenceIndex = outputSequenceIndex[orderChannel.toInt()]++
        } else if (frame.reliability.isOrdered) {
            orderIndex = outputOrderIndex[orderChannel.toInt()]++
            outputSequenceIndex[orderChannel.toInt()] = 0u
        }

        val frames = payloads.mapIndexed { i, payload ->
            var frame = Frame(
                reliability = reliability,
                payload = payload,
                reliableIndex = when (reliability.isReliable) {
                    true -> outReliableIndex++
                    false -> 0u
                },
                sequenceIndex = sequenceIndex,
                orderIndex = orderIndex,
                orderChannel = orderChannel,
            )

            if (payloads.size > 1) {
                frame = frame.copy(
                    splitSize = payloads.size.toUInt(),
                    splitID = splitID,
                    splitIndex = i.toUInt()
                )
            }

            frame
        }

        queueFrames(frames, priority)
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
                        continuousSend = batch.any(Frame::isSplit),
                        needsBAndAS = true,
                        isPair = false,
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
                continuousSend = batch.any(Frame::isSplit),
                needsBAndAS = true,
                isPair = false,
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

        log.debug { "RakSession closed by $address" }
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