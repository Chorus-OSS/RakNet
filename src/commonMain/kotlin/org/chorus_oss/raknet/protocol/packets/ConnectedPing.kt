package org.chorus_oss.raknet.protocol.packets

import kotlinx.datetime.Clock
import kotlinx.io.*
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.session.RakSession
import org.chorus_oss.raknet.types.RakPacketID
import org.chorus_oss.raknet.types.RakPriority
import org.chorus_oss.raknet.types.RakReliability

data class ConnectedPing(
    val timestamp: ULong,
) {
    companion object : RakPacketCodec<ConnectedPing> {
        override val id: UByte
            get() = RakPacketID.CONNECTED_PING

        override fun serialize(value: ConnectedPing, stream: Sink) {
            stream.writeUByte(id) // Packet ID
            stream.writeULong(value.timestamp)
        }

        override fun deserialize(stream: Source): ConnectedPing {
            stream.readUByte() // Packet ID
            return ConnectedPing(
                timestamp = stream.readULong()
            )
        }

        fun RakSession.handleConnectedPing(stream: Source) {
            val ping = deserialize(stream)

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
    }
}
