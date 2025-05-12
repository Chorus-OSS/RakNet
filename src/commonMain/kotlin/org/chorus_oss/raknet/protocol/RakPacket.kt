package org.chorus_oss.raknet.protocol

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readUByte
import kotlinx.io.writeUByte
import org.chorus_oss.raknet.protocol.packets.*

open class RakPacket(val id: UByte) {
    companion object {
        private val log = KotlinLogging.logger { }

        val registry: Map<UByte, RakPacketCodec<out RakPacket>> by lazy {
            mapOf(
                Ack.id to Ack,
                ConnectedPing.id to ConnectedPing,
                ConnectedPong.id to ConnectedPong,
                ConnectionRequest.id to ConnectionRequest,
                ConnectionRequestAccepted.id to ConnectionRequestAccepted,
                Disconnect.id to Disconnect,
                FrameSet.id to FrameSet,
                IncompatibleProtocol.id to IncompatibleProtocol,
                NAck.id to NAck,
                NewIncomingConnection.id to NewIncomingConnection,
                OpenConnectionReply1.id to OpenConnectionReply1,
                OpenConnectionReply2.id to OpenConnectionReply2,
                OpenConnectionRequest1.id to OpenConnectionRequest1,
                OpenConnectionRequest2.id to OpenConnectionRequest2,
                UnconnectedPing.id to UnconnectedPing,
                UnconnectedPong.id to UnconnectedPong,
            )
        }

        fun <T : RakPacket> serialize(value: T): Buffer {
            val buffer = Buffer()

            @Suppress("UNCHECKED_CAST")
            val codec = registry[value.id] as? RakPacketCodec<T>
            if (codec == null) {
                log.error { "Couldn't find PacketCodec for id: ${value.id}" }
                return buffer
            }

            buffer.writeUByte(value.id)
            codec.serialize(value, buffer)

            return buffer
        }

        fun deserialize(stream: Source): RakPacket {
            val id = stream.readUByte()

            @Suppress("UNCHECKED_CAST")
            val codec = registry[id] as? RakPacketCodec<RakPacket>
            if (codec == null) {
                log.error { "Couldn't find PacketCodec for id: $id" }
                return RakPacket(id)
            }

            return codec.deserialize(stream)
        }
    }
}