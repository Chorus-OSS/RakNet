package org.chorus_oss.raknet.protocol

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.Buffer
import org.chorus_oss.raknet.protocol.packets.*

open class Packet(val id: UByte) {
    companion object {
        private val log = KotlinLogging.logger { }

        val registry: Map<UByte, PacketCodec<out Packet>> by lazy {
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

        fun <T : Packet> serialize(value: T): Buffer {
            val buffer = Buffer()

            @Suppress("UNCHECKED_CAST")
            val codec = registry[value.id] as? PacketCodec<T>
            if (codec == null) {
                log.error { "Couldn't find PacketCodec for id: ${value.id}" }
                return buffer
            }

            codec.serialize(value, buffer)

            return buffer
        }
    }
}