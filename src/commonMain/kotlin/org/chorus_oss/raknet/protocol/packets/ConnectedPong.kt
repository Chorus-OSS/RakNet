package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readULong
import kotlinx.io.writeULong
import org.chorus_oss.raknet.protocol.Packet
import org.chorus_oss.raknet.protocol.PacketCodec
import org.chorus_oss.raknet.types.PacketHeader

data class ConnectedPong(
    val pingTimestamp: ULong,
    val timestamp: ULong,
) : Packet(id) {
    companion object : PacketCodec<ConnectedPong> {
        override val id: UByte
            get() = PacketHeader.CONNECTED_PONG

        override fun serialize(value: ConnectedPong, stream: Sink) {
            stream.writeULong(value.pingTimestamp)
            stream.writeULong(value.timestamp)
        }

        override fun deserialize(stream: Source): ConnectedPong {
            return ConnectedPong(
                pingTimestamp = stream.readULong(),
                timestamp = stream.readULong(),
            )
        }
    }
}
