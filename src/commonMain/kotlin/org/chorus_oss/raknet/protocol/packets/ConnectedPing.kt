package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readULong
import kotlinx.io.writeULong
import org.chorus_oss.raknet.protocol.Codec
import org.chorus_oss.raknet.protocol.Packet
import org.chorus_oss.raknet.protocol.PacketCodec
import org.chorus_oss.raknet.types.PacketHeader

data class ConnectedPing(
    val timestamp: ULong,
) : Packet(id) {
    companion object : PacketCodec<ConnectedPing> {
        override val id: UByte
            get() = PacketHeader.CONNECTED_PING

        override fun serialize(value: ConnectedPing, stream: Sink) {
            stream.writeULong(value.timestamp)
        }

        override fun deserialize(stream: Source): ConnectedPing {
            return ConnectedPing(
                timestamp = stream.readULong()
            )
        }
    }
}
