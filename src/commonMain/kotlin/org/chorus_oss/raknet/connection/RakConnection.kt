package org.chorus_oss.raknet.connection

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.readUByte
import org.chorus_oss.raknet.protocol.RakPacket
import org.chorus_oss.raknet.protocol.packets.*
import org.chorus_oss.raknet.protocol.types.Address
import org.chorus_oss.raknet.protocol.types.Frame
import org.chorus_oss.raknet.protocol.types.UMedium
import org.chorus_oss.raknet.server.RakServer
import org.chorus_oss.raknet.types.*
import kotlin.math.ceil
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class RakConnection(
    private val server: RakServer,
    private val address: SocketAddress,
    val guid: ULong,
    val mtu: UShort,
) {
    var status: RakStatus = RakStatus.Connecting
    var lastUpdate: Instant = Clock.System.now()

    val receivedFrameSequences = mutableSetOf<UInt>()
    val lostFrameSequences = mutableSetOf<UInt>()
    val inputHighestSequenceIndex = MutableList<UInt>(32) { 0u }
    val fragmentsQueue = mutableMapOf<UShort, MutableMap<UInt, Frame>>()

    val inputOrderIndex = MutableList<UMedium>(32) { 0u }
    val inputOrderingQueue = mutableMapOf<UByte, MutableMap<UMedium, Frame>>(
        *(Array(32) { Pair(0u.toUByte(), mutableMapOf()) }),
    )
    var lastInputSequence: UInt? = null;

    val outputOrderIndex = MutableList<UMedium>(32) { 0u }
    val outputSequenceIndex = MutableList<UMedium>(32) { 0u }

    val outputFrames = mutableSetOf<Frame>()
    val outputBackup = mutableMapOf<UInt, List<Frame>>()

    var outputSequence: UInt = 0u
    var outputSplitIndex: UInt = 0u
    var outputReliableIndex: UMedium = 0u

    suspend fun tick() {
        if (lastUpdate.plus(15000.toDuration(DurationUnit.MILLISECONDS)) < Clock.System.now()) {
            log.warn { "Detected stale connection from $address, disconnecting..." }

            return disconnect()
        }

        if (status == RakStatus.Disconnecting || status == RakStatus.Disconnected) {
            return
        }

        if (receivedFrameSequences.isNotEmpty()) {
            val ack = Ack(
                sequences = receivedFrameSequences.toList(),
            )

            receivedFrameSequences.clear()

            server.send(Datagram(RakPacket.serialize(ack), address))
        }

        if (lostFrameSequences.isNotEmpty()) {
            val nack = NAck(
                sequences = lostFrameSequences.toList(),
            )

            lostFrameSequences.clear()

            server.send(Datagram(RakPacket.serialize(nack), address))
        }

        this.sendQueue(outputFrames.size)
    }

    suspend fun disconnect() {
        status = RakStatus.Disconnecting

        val disconnect = Disconnect()

        val frame = Frame(
            reliability = RakReliability.ReliableOrdered,
            orderChannel = 0u,
            payload = RakPacket.serialize(disconnect)
        )

        sendFrame(frame, RakPriority.Immediate)

        // disconnect event
        server.connections.remove(address)

        status = RakStatus.Disconnected
    }

    suspend fun incoming(stream: Source) {
        lastUpdate = Clock.System.now()

        val header = stream.peek().readUByte() and 0xF0.toUByte()

        when (header) {
            RakPacketID.ACK -> ack(stream)
            RakPacketID.NACK -> nack(stream)
            RakHeader.VALID -> handleIncomingFrameSet(stream)

            else -> {
                val id = header.toString(16).padStart(2, '0').uppercase()

                log.debug { "Received unknown online packet \"0x$id\" from $address" }

                // error event
            }
        }
    }

    private suspend fun incomingBatch(stream: Source) {
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
                    // connected event
                }

                else -> {
                    val id = header.toString(16).padStart(2, '0').uppercase()

                    log.debug { "Received unknown online packet \"0x$id\" from $address" }

                    // error event
                }
            }

            return
        }

        when (header) {
            RakPacketID.DISCONNECT -> {
                status = RakStatus.Disconnecting
                // disconnect event
                server.connections.remove(address)
                status = RakStatus.Disconnected
            }

            RakPacketID.CONNECTED_PING -> {
                handleIncomingConnectedPing(stream)
            }

            0xFE.toUByte() -> {
                // gamepacked event (encapsulated)
            }

            else -> {
                val id = header.toString(16).padStart(2, '0').uppercase()

                log.debug { "Received unknown online packet \"0x$id\" from $address" }

                // error event
            }
        }
    }

    private fun ack(stream: Source) {
        val ack = RakPacket.deserialize(stream) as Ack

        for (sequence in ack.sequences) {
            if (!outputBackup.contains(sequence)) {
                log.debug { "Received ack for unknown sequence $sequence from $address" }
            }

            outputBackup.remove(sequence)
        }
    }

    private suspend fun nack(stream: Source) {
        val nack = RakPacket.deserialize(stream) as NAck

        for (sequence in nack.sequences) {
            val frames = outputBackup[sequence] ?: emptyList()
            for (frame in frames) {
                sendFrame(frame, RakPriority.Immediate)
            }
        }
    }

    private suspend fun handleIncomingFrameSet(stream: Source) {
        stream.readUByte()
        val frameSet = FrameSet.deserialize(stream)

        if (receivedFrameSequences.contains(frameSet.sequence)) {
            log.debug { "Received duplicate frameset ${frameSet.sequence} from $address" }

            return // error event
        }

        lostFrameSequences.remove(frameSet.sequence)

        if (lastInputSequence != null && (frameSet.sequence < lastInputSequence!! || frameSet.sequence == lastInputSequence!!)) {
            log.debug { "Received out of order frameset ${frameSet.sequence} from $address" }

            return // error event
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

    private suspend fun handleFrame(frame: Frame) {
        if (frame.isSplit) return handleFragment(frame)

        if (frame.reliability.isSequenced) {
            if (
                frame.sequenceIndex < (inputHighestSequenceIndex[frame.orderChannel.toInt()]) ||
                frame.orderIndex < (inputOrderIndex[frame.orderChannel.toInt()])
            ) {
                log.debug { "Received out of order frame ${frame.sequenceIndex} from $address" }

                return // error event
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

    private suspend fun handleFragment(frame: Frame) {
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

    suspend fun sendFrame(frame: Frame, priority: RakPriority) {
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

    private suspend fun queueFrame(frame: Frame, priority: RakPriority) {
        var length = Rak.DGRAM_HEADER_SIZE.toLong()

        for (f in outputFrames) {
            length += f.byteLength
        }

        if (length + frame.byteLength > (mtu - Rak.DGRAM_MTU_OVERHEAD).toLong()) {
            sendQueue(outputFrames.size)
        }

        outputFrames.add(frame)

        if (priority == RakPriority.Immediate) sendQueue(1)
    }

    suspend fun sendQueue(amount: Int) {
        if (outputFrames.isEmpty()) return

        val frameSet = FrameSet(
            sequence = outputSequence++,
            frames = outputFrames.take(amount).toList(),
        )

        outputBackup[frameSet.sequence] = frameSet.frames

        for (frame in frameSet.frames) {
            outputFrames.remove(frame)
        }

        server.send(Datagram(RakPacket.serialize(frameSet), address))
    }

    private suspend fun handleIncomingConnectionRequest(stream: Source) {
        val request = RakPacket.deserialize(stream) as ConnectionRequest

        val accepted = ConnectionRequestAccepted(
            clientAddress = Address(address as InetSocketAddress),
            systemIndex = 0u,
            systemAddress = emptyList(),
            requestTimestamp = request.clientTimestamp,
            timestamp = Clock.System.now().toEpochMilliseconds().toULong()
        )

        val frame = Frame(
            reliability = RakReliability.ReliableOrdered,
            orderChannel = 0u,
            payload = RakPacket.serialize(accepted),
        )

        sendFrame(frame, RakPriority.Normal)
    }

    private suspend fun handleIncomingConnectedPing(stream: Source) {
        val ping = RakPacket.deserialize(stream) as ConnectedPing

        val pong = ConnectedPong(
            pingTimestamp = ping.timestamp,
            timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
        )

        val frame = Frame(
            reliability = RakReliability.ReliableOrdered,
            orderChannel = 0u,
            payload = RakPacket.serialize(pong),
        )

        sendFrame(frame, RakPriority.Normal)
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}