package org.chorus_oss.raknet.connection

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.readUByte
import org.chorus_oss.raknet.protocol.packets.*
import org.chorus_oss.raknet.protocol.types.Address
import org.chorus_oss.raknet.protocol.types.Frame
import org.chorus_oss.raknet.protocol.types.UMedium
import org.chorus_oss.raknet.server.RakServer
import org.chorus_oss.raknet.types.*
import kotlin.math.ceil
import kotlin.time.Duration.Companion.milliseconds

class RakConnection(
    val server: RakServer,
    val address: SocketAddress,
    val guid: ULong,
    val mtu: UShort,
) {
    private var status: RakStatus = RakStatus.Connecting
    private var lastUpdate: Instant = Clock.System.now()

    private val outbound: Channel<Datagram> = Channel(Channel.UNLIMITED)

    private val receivedFrameSequences = mutableSetOf<UInt>()
    private val lostFrameSequences = mutableSetOf<UInt>()
    private val inputHighestSequenceIndex = MutableList(32) { 0u }
    private val fragmentsQueue = mutableMapOf<UShort, MutableMap<UInt, Frame>>()

    private val inputOrderIndex = MutableList(32) { 0u }
    private val inputOrderingQueue = mutableMapOf<UByte, MutableMap<UMedium, Frame>>(
        *(Array(32) { Pair(0u.toUByte(), mutableMapOf()) }),
    )
    private var lastInputSequence: UInt? = null;

    private val outputOrderIndex = MutableList(32) { 0u }
    private val outputSequenceIndex = MutableList(32) { 0u }

    private val outputFrames = mutableSetOf<Frame>()
    private val outputBackup = mutableMapOf<UInt, List<Frame>>()

    private var outputSequence: UInt = 0u
    private var outputSplitIndex: UInt = 0u
    private var outputReliableIndex: UMedium = 0u

    var onPacket: (Source) -> Unit = {}
    var onError: (Error) -> Unit = {}

    fun onPacket(onPacket: (Source) -> Unit): RakConnection {
        this.onPacket = onPacket
        return this
    }

    fun onError(onError: (Error) -> Unit): RakConnection {
        this.onError = onError
        return this
    }

    val tickJob: Job = server.launch {
        while(isActive) {
            tick()
            delay(10)
        }
    }

    fun flush() {
        while (true) {
            val out = outbound.tryReceive().getOrNull() ?: break
            server.outbound.trySend(out)
        }
    }

    fun tick() {
        if (status == RakStatus.Disconnecting || status == RakStatus.Disconnected) {
            return
        }

        if (lastUpdate.plus(15000.milliseconds) < Clock.System.now()) {
            log.warn { "Detected stale connection from $address, disconnecting..." }

            return disconnect()
        }


        if (receivedFrameSequences.isNotEmpty()) {
            val ack = Ack(
                sequences = receivedFrameSequences.toList(),
            )

            receivedFrameSequences.clear()

            outbound.trySend(
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

            outbound.trySend(
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

    fun disconnect() {
        status = RakStatus.Disconnecting

        val disconnect = Disconnect()

        val frame = Frame(
            reliability = RakReliability.ReliableOrdered,
            orderChannel = 0u,
            payload = Buffer().also {
                Disconnect.serialize(disconnect, it)
            }
        )

        sendFrame(frame, RakPriority.Immediate)

        server.config.onDisconnect(this)
        server.connections.remove(address)

        status = RakStatus.Disconnected

        tickJob.cancel()
    }

    fun incoming(stream: Source) {
        lastUpdate = Clock.System.now()

        val header = stream.peek().readUByte() and 0xF0.toUByte()

        when (header) {
            RakPacketID.ACK -> ack(stream)
            RakPacketID.NACK -> nack(stream)
            RakHeader.VALID -> handleIncomingFrameSet(stream)

            else -> {
                val id = header.toString(16).padStart(2, '0').uppercase()

                log.debug { "Received unknown online packet \"0x$id\" from $address" }

                onError(Error("Received unknown online packet \"0x$id\" from $address"))
            }
        }
    }

    private fun incomingBatch(stream: Source) {
        val header = stream.peek().readUByte()

        if (status == RakStatus.Connecting) {
            when (header) {
                RakPacketID.DISCONNECT -> {
                    status = RakStatus.Disconnecting
                    server.connections.remove(address)
                    status = RakStatus.Disconnected
                }

                RakPacketID.CONNECTION_REQUEST -> {
                    handleIncomingConnectionRequest(stream)
                }

                RakPacketID.NEW_INCOMING_CONNECTION -> {
                    status = RakStatus.Connected
                    server.config.onConnect(this)
                }

                else -> {
                    val id = header.toString(16).padStart(2, '0').uppercase()

                    log.debug { "Received unknown online packet \"0x$id\" from $address" }

                    onError(Error("Received unknown online packet \"0x$id\" from $address"))
                }
            }

            return
        }

        when (header) {
            RakPacketID.DISCONNECT -> {
                status = RakStatus.Disconnecting
                server.config.onDisconnect(this)
                server.connections.remove(address)
                status = RakStatus.Disconnected
            }

            RakPacketID.CONNECTED_PING -> {
                handleIncomingConnectedPing(stream)
            }

            0xFE.toUByte() -> {
                onPacket(stream)
            }

            else -> {
                val id = header.toString(16).padStart(2, '0').uppercase()

                log.debug { "Received unknown online packet \"0x$id\" from $address" }

                onError(Error("Received unknown online packet \"0x$id\" from $address"))
            }
        }
    }

    private fun ack(stream: Source) {
        val ack = Ack.deserialize(stream)

        for (sequence in ack.sequences) {
            if (!outputBackup.contains(sequence)) {
                log.debug { "Received ack for unknown sequence $sequence from $address" }
            }

            outputBackup.remove(sequence)
        }
    }

    private fun nack(stream: Source) {
        val nack = NAck.deserialize(stream)

        for (sequence in nack.sequences) {
            val frames = outputBackup[sequence] ?: emptyList()
            for (frame in frames) {
                sendFrame(frame, RakPriority.Immediate)
            }
        }
    }

    private fun handleIncomingFrameSet(stream: Source) {
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

            return incomingBatch(frame.payload)
        }

        if (frame.reliability.isOrdered) {
            if (frame.orderIndex == inputOrderIndex[frame.orderChannel.toInt()]) {
                inputHighestSequenceIndex[frame.orderChannel.toInt()] = 0u
                inputOrderIndex[frame.orderChannel.toInt()] = frame.orderIndex + 1u

                incomingBatch(frame.payload)

                var index = inputOrderIndex[frame.orderChannel.toInt()]
                val outOfOrderQueue = inputOrderingQueue[frame.orderChannel]!!

                while (outOfOrderQueue.contains(index)) {
                    index++

                    val f = outOfOrderQueue[index] ?: return

                    incomingBatch(f.payload)
                    outOfOrderQueue.remove(index)
                }

                inputOrderingQueue[frame.orderChannel] = outOfOrderQueue
                inputOrderIndex[frame.orderChannel.toInt()] = index
            } else if (frame.orderIndex > inputOrderIndex[frame.orderChannel.toInt()]) {
                val unordered = inputOrderingQueue[frame.orderChannel] ?: return

                unordered[frame.orderIndex] = frame
            }
        } else {
            return incomingBatch(frame.payload)
        }
    }

    private fun handleFragment(frame: Frame) {
        if (fragmentsQueue.contains(frame.splitID)) {
            val fragment = fragmentsQueue[frame.splitID] ?: return

            fragment[frame.splitIndex] = frame

            if (fragment.size.toUInt() == frame.splitSize) {
                val stream = Buffer()

                for (f in fragment.values) {
                    stream.write(f.payload.readByteArray())
                }

                val nFrame = frame.copy(payload = stream)

                fragmentsQueue.remove(frame.splitID)

                return handleFrame(nFrame)
            } else {
                fragmentsQueue[frame.splitID] = mutableMapOf(Pair(frame.splitIndex, frame))
            }
        }
    }

    fun sendFrame(frame: Frame, priority: RakPriority) {
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

            for (i in 0 until frame.payload.remaining / maxSize.toInt()) {
                val subBuf = Buffer()
                frame.payload.readTo(subBuf, maxSize.toLong())

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

    fun sendQueue(amount: Int) {
        if (outputFrames.isEmpty()) return

        val frameSet = FrameSet(
            sequence = outputSequence++,
            frames = outputFrames.take(amount).toList(),
        )

        outputBackup[frameSet.sequence] = frameSet.frames

        for (frame in frameSet.frames) {
            outputFrames.remove(frame)
        }

        outbound.trySend(
            Datagram(
                packet = Buffer().also {
                    FrameSet.serialize(frameSet, it)
                },
                address = address
            )
        )
    }

    private fun handleIncomingConnectionRequest(stream: Source) {
        val request = ConnectionRequest.deserialize(stream)

        val accepted = ConnectionRequestAccepted(
            clientAddress = Address.from(address as InetSocketAddress),
            systemIndex = 0u,
            systemAddress = emptyList(),
            requestTimestamp = request.clientTimestamp,
            timestamp = Clock.System.now().toEpochMilliseconds().toULong()
        )

        val frame = Frame(
            reliability = RakReliability.ReliableOrdered,
            orderChannel = 0u,
            payload = Buffer().also {
                ConnectionRequestAccepted.serialize(accepted, it)
            },
        )

        sendFrame(frame, RakPriority.Normal)
    }

    private fun handleIncomingConnectedPing(stream: Source) {
        val ping = ConnectedPing.deserialize(stream)

        val pong = ConnectedPong(
            pingTimestamp = ping.timestamp,
            timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
        )

        val frame = Frame(
            reliability = RakReliability.ReliableOrdered,
            orderChannel = 0u,
            payload = Buffer().also {
                ConnectedPong.serialize(pong, it)
            },
        )

        sendFrame(frame, RakPriority.Normal)
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}