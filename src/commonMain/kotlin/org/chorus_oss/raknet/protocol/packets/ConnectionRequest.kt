package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readULong
import kotlinx.io.writeULong
import org.chorus_oss.raknet.protocol.PacketCodec
import org.chorus_oss.raknet.types.PacketHeader

data class ConnectionRequest(
    val clientGuid: ULong,
    val clientTimestamp: ULong,
) {
    companion object : PacketCodec<ConnectionRequest> {
        override val id: UByte
            get() = PacketHeader.CONNECTION_REQUEST

        override fun serialize(value: ConnectionRequest, stream: Sink) {
            stream.writeULong(value.clientGuid)
            stream.writeULong(value.clientTimestamp)
        }

        override fun deserialize(stream: Source): ConnectionRequest {
            return ConnectionRequest(
                clientGuid = stream.readULong(),
                clientTimestamp = stream.readULong(),
            )
        }
    }
}
