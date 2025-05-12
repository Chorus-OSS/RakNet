package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readULong
import kotlinx.io.writeULong
import org.chorus_oss.raknet.protocol.RakPacket
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.types.RakPacketID

data class ConnectedPong(
    val pingTimestamp: ULong,
    val timestamp: ULong,
) : RakPacket(id) {
    companion object : RakPacketCodec<ConnectedPong> {
        override val id: UByte
            get() = RakPacketID.CONNECTED_PONG

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
