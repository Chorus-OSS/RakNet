package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.types.RakPacketID

data class ConnectedPong(
    val pingTimestamp: ULong,
    val timestamp: ULong,
) {
    companion object : RakPacketCodec<ConnectedPong> {
        override val id: UByte
            get() = RakPacketID.CONNECTED_PONG

        override fun serialize(value: ConnectedPong, stream: Sink) {
            stream.writeUByte(id) // Packet ID
            stream.writeULong(value.pingTimestamp)
            stream.writeULong(value.timestamp)
        }

        override fun deserialize(stream: Source): ConnectedPong {
            stream.readUByte() // Packet ID
            return ConnectedPong(
                pingTimestamp = stream.readULong(),
                timestamp = stream.readULong(),
            )
        }
    }
}
