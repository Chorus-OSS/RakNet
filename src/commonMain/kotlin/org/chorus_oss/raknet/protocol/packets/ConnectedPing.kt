package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.types.RakPacketID

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
    }
}
