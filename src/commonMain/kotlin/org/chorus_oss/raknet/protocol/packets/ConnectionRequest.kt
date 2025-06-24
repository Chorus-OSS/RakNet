package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.types.RakPacketID

data class ConnectionRequest(
    val clientGuid: ULong,
    val clientTimestamp: ULong,
    val security: Boolean = false,
) {
    companion object : RakPacketCodec<ConnectionRequest> {
        override val id: UByte
            get() = RakPacketID.CONNECTION_REQUEST

        override fun serialize(value: ConnectionRequest, stream: Sink) {
            stream.writeUByte(id) // Packet ID
            stream.writeULong(value.clientGuid)
            stream.writeULong(value.clientTimestamp)
            stream.writeByte(if (value.security) 1 else 0)
        }

        override fun deserialize(stream: Source): ConnectionRequest {
            stream.readUByte() // Packet ID
            return ConnectionRequest(
                clientGuid = stream.readULong(),
                clientTimestamp = stream.readULong(),
                security = stream.readByte() == 1.toByte()
            )
        }
    }
}
