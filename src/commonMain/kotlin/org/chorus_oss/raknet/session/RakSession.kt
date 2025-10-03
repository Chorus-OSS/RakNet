package org.chorus_oss.raknet.session

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.readUByte
import org.chorus_oss.raknet.protocol.packets.*
import org.chorus_oss.raknet.protocol.types.Frame
import org.chorus_oss.raknet.types.*
import kotlin.coroutines.CoroutineContext
import kotlin.math.ceil
import kotlin.time.Duration.Companion.milliseconds

abstract class RakSession(
    context: CoroutineContext,
    private val outbound: SendChannel<Datagram>,
    val address: InetSocketAddress,
    val guid: ULong,
    val mtu: UShort,
) : CoroutineScope {
    override val coroutineContext: CoroutineContext = context

    protected var state: RakSessionState = RakSessionState.Connecting
    private var lastUpdate: Instant = Clock.System.now()

    private val queued: Channel<Datagram> = Channel(Channel.UNLIMITED)

    private val receivedFrameSequences = mutableSetOf<UInt>()
    private val lostFrameSequences = mutableSetOf<UInt>()
    private val inputHighestSequenceIndex = MutableList(32) { 0u }
    private val fragmentsQueue = mutableMapOf<UShort, MutableMap<UInt, Frame>>()

    private val inputOrderIndex = MutableList(32) { 0u }
    private val inputOrderingQueue = mutableMapOf<UByte, MutableMap<UInt, Frame>>(
        *(Array(32) { Pair(0u.toUByte(), mutableMapOf()) }),
    )
    private var lastInputSequence: UInt? = null

    private val outputOrderIndex = MutableList(32) { 0u }
    private val outputSequenceIndex = MutableList(32) { 0u }

    private val outputFrames = mutableSetOf<Frame>()
    private val outputBackup = mutableMapOf<UInt, List<Frame>>()

    private var outputSequence: UInt = 0u
    private var outputSplitIndex: UInt = 0u
    private var outputReliableIndex: UInt = 0u

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

    private val tick: Job = launch {
        while (isActive) {
            tick()
            delay(10)
        }
    }

    fun flush() {
        while (true) {
            val out = queued.tryReceive().getOrNull() ?: break
            outbound.trySend(out)
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

        this.sendQueue(outputFrames.size)

        flush()
    }

    protected fun disconnect(send: Boolean, connected: Boolean) {
        state = RakSessionState.Disconnecting

        if (send) {
            val disconnect = Disconnect()

            val frame = Frame(
                reliability = RakReliability.ReliableOrdered,
                orderChannel = 0u,
                payload = Buffer().also {
                    Disconnect.serialize(disconnect, it)
                }
            )

            sendFrame(frame, RakPriority.Immediate)
        }

        if (connected) onDisconnect()

        state = RakSessionState.Disconnected

        tick.cancel()
    }

    fun inbound(stream: Source) {
        lastUpdate = Clock.System.now()

        val header = stream.peek().readUByte() and 0xF0.toUByte()

        when (header) {
            RakPacketID.ACK -> handleACK(stream)
            RakPacketID.NACK -> handleNACK(stream)
            RakHeader.VALID -> handleFrameSet(stream)

            else -> {
                val id = header.toString(16).padStart(2, '0').uppercase()

                log.debug { "Received unknown online packet \"0x$id\" from $address" }

                onError(Error("Received unknown online packet \"0x$id\" from $address"))
            }
        }
    }

    protected abstract fun handle(stream: Source)

    protected abstract fun onConnect()

    protected abstract fun onDisconnect()

    private fun handleACK(stream: Source) {
        val ack = Ack.deserialize(stream)

        for (sequence in ack.sequences) {
            if (!outputBackup.contains(sequence)) {
                log.debug { "Received ack for unknown sequence $sequence from $address" }
            }

            outputBackup.remove(sequence)
        }
    }

    private fun handleNACK(stream: Source) {
        val nack = NAck.deserialize(stream)

        for (sequence in nack.sequences) {
            val frames = outputBackup[sequence] ?: emptyList()
            for (frame in frames) {
                sendFrame(frame, RakPriority.Immediate)
            }
        }
    }

    private fun handleFrameSet(stream: Source) {
        val frameSet = FrameSet.deserialize(stream)

        if (receivedFrameSequences.contains(frameSet.sequence)) {
            log.debug { "Received duplicate frameset ${frameSet.sequence} from $address" }

            return onError(Error("Received duplicate frameset ${frameSet.sequence} from $address"))
        }

        lostFrameSequences.remove(frameSet.sequence)

        if (lastInputSequence != null && (frameSet.sequence < lastInputSequence!! || frameSet.sequence == lastInputSequence!!)) {
            log.debug { "Received out of order frameset ${frameSet.sequence} from $address" }

            return onError(Error("Received out of order frameset ${frameSet.sequence} from $address"))
        }

        receivedFrameSequences.add(frameSet.sequence)

        if (lastInputSequence != null && (frameSet.sequence - lastInputSequence!! > 1u)) {
            for (i in lastInputSequence!! + 1u until frameSet.sequence) {
                lostFrameSequences.add(i)
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

                return onError(Error("Received out of order frame ${frame.sequenceIndex} from $address"))
            }

            inputHighestSequenceIndex[frame.orderChannel.toInt()] = frame.sequenceIndex + 1u

            return handle(frame.payload)
        }

        if (frame.reliability.isOrdered) {
            if (frame.orderIndex == inputOrderIndex[frame.orderChannel.toInt()]) {
                inputHighestSequenceIndex[frame.orderChannel.toInt()] = 0u
                inputOrderIndex[frame.orderChannel.toInt()] = frame.orderIndex + 1u

                handle(frame.payload)

                var index = inputOrderIndex[frame.orderChannel.toInt()]
                val outOfOrderQueue = inputOrderingQueue[frame.orderChannel]!!

                while (outOfOrderQueue.contains(index)) {
                    index++

                    val f = outOfOrderQueue[index] ?: return

                    handle(f.payload)
                    outOfOrderQueue.remove(index)
                }

                inputOrderingQueue[frame.orderChannel] = outOfOrderQueue
                inputOrderIndex[frame.orderChannel.toInt()] = index
            } else if (frame.orderIndex > inputOrderIndex[frame.orderChannel.toInt()]) {
                val unordered = inputOrderingQueue[frame.orderChannel] ?: return

                unordered[frame.orderIndex] = frame
            }
        } else {
            return handle(frame.payload)
        }
    }

    private fun handleFragment(frame: Frame) {
        val fragment = fragmentsQueue.getOrPut(frame.splitID) { mutableMapOf() }

        fragment[frame.splitIndex] = frame

        if (fragment.size.toUInt() == frame.splitSize) {
            val stream = Buffer()

            for (i in 0u until frame.splitSize) {
                val f = fragment[i] ?: return
                stream.write(f.payload.readByteArray())
            }

            val nFrame = frame.copy(
                payload = stream,
                splitSize = 0u,
                splitID = 0u,
                splitIndex = 0u,
            )

            fragmentsQueue.remove(frame.splitID)

            return handleFrame(nFrame)
        }
    }

    private fun sendFrame(frame: Frame, priority: RakPriority) {
        if (frame.reliability.isSequenced) {
            frame.orderIndex = outputOrderIndex[frame.orderChannel.toInt()]
            frame.sequenceIndex = outputSequenceIndex[frame.orderChannel.toInt()]++
        } else if (frame.reliability.isOrderExclusive) {
            frame.orderIndex = outputOrderIndex[frame.orderChannel.toInt()]++
            outputSequenceIndex[frame.orderChannel.toInt()] = 0u
        }

        val maxSize = mtu - 36u
        val splitSize = ceil(frame.payload.remaining.toFloat() / maxSize.toFloat()).toUInt()

        frame.reliableIndex = outputReliableIndex++

        if (frame.payload.remaining > maxSize.toLong()) {
            val splitId = (outputSplitIndex++ % 65536u).toUShort()

            for (i in 0 until splitSize.toInt()) {
                val subBuf = Buffer()
                frame.payload.readAtMostTo(subBuf, maxSize.toLong())

                val nFrame = frame.copy(
                    payload = subBuf,
                    splitIndex = i.toUInt(),
                    splitID = splitId,
                    splitSize = splitSize
                )

                queueFrame(nFrame, priority)
            }
        } else {
            queueFrame(frame, priority)
        }
    }

    private fun queueFrame(frame: Frame, priority: RakPriority) {
        var length = RakConstants.DGRAM_HEADER_SIZE.toLong()

        for (f in outputFrames) {
            length += f.byteLength
        }

        if (length + frame.byteLength > (mtu - RakConstants.DGRAM_MTU_OVERHEAD).toLong()) {
            sendQueue(outputFrames.size)
        }

        outputFrames.add(frame)

        if (priority == RakPriority.Immediate) {
            sendQueue(1)
            flush()
        }
    }

    private fun sendQueue(amount: Int) {
        if (outputFrames.isEmpty()) return

        val frameSet = FrameSet(
            sequence = outputSequence++,
            frames = outputFrames.take(amount).toList(),
        )

        outputBackup[frameSet.sequence] = frameSet.frames

        for (frame in frameSet.frames) {
            outputFrames.remove(frame)
        }

        queued.trySend(
            Datagram(
                packet = Buffer().also {
                    FrameSet.serialize(frameSet, it)
                },
                address = address
            )
        )
    }

    fun send(packet: Buffer, reliability: RakReliability, priority: RakPriority) {
        sendFrame(
            Frame(
                payload = packet,
                reliability = reliability,
            ),
            priority
        )
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}