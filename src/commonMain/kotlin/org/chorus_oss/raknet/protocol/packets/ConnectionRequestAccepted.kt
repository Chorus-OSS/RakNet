package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import org.chorus_oss.raknet.protocol.Packet
import org.chorus_oss.raknet.protocol.PacketCodec
import org.chorus_oss.raknet.protocol.types.Address
import org.chorus_oss.raknet.protocol.types.SystemAddress
import org.chorus_oss.raknet.types.PacketHeader

data class ConnectionRequestAccepted(
    val clientAddress: Address,
    val systemIndex: UShort,
    val systemAddress: List<Address>,
    val requestTimestamp: ULong,
    val timestamp: ULong,
) : Packet(id) {
    companion object : PacketCodec<ConnectionRequestAccepted> {
        override val id: UByte
            get() = PacketHeader.CONNECTION_REQUEST_ACCEPTED

        override fun serialize(value: ConnectionRequestAccepted, stream: Sink) {
            Address.serialize(value.clientAddress, stream)
            stream.writeUShort(value.systemIndex)
            SystemAddress.serialize(value.systemAddress, stream)
            stream.writeULong(value.requestTimestamp)
            stream.writeULong(value.timestamp)
        }

        override fun deserialize(stream: Source): ConnectionRequestAccepted {
            return ConnectionRequestAccepted(
                clientAddress = Address.deserialize(stream),
                systemIndex = stream.readUShort(),
                systemAddress = SystemAddress.deserialize(stream),
                requestTimestamp = stream.readULong(),
                timestamp = stream.readULong()
            )
        }
    }
}
