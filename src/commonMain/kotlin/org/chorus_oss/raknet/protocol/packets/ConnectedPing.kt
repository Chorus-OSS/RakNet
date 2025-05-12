package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readULong
import kotlinx.io.writeULong
import org.chorus_oss.raknet.protocol.RakPacket
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.types.RakPacketID

data class ConnectedPing(
    val timestamp: ULong,
) : RakPacket(id) {
    companion object : RakPacketCodec<ConnectedPing> {
        override val id: UByte
            get() = RakPacketID.CONNECTED_PING

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
