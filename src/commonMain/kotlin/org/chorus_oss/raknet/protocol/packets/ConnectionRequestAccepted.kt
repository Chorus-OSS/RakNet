package org.chorus_oss.raknet.protocol.packets

import io.ktor.utils.io.core.*
import kotlinx.io.*
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.protocol.types.Address
import org.chorus_oss.raknet.types.RakPacketID

data class ConnectionRequestAccepted(
    val clientAddress: Address,
    val systemIndex: UShort,
    val systemAddresses: List<Address>,
    val requestTimestamp: ULong,
    val timestamp: ULong,
) {
    companion object : RakPacketCodec<ConnectionRequestAccepted> {
        override val id: UByte
            get() = RakPacketID.CONNECTION_REQUEST_ACCEPTED

        override fun serialize(value: ConnectionRequestAccepted, stream: Sink) {
            stream.writeUByte(id) // Packet ID
            Address.serialize(value.clientAddress, stream)
            stream.writeUShort(value.systemIndex)
            value.systemAddresses.forEach { Address.serialize(it, stream) }
            stream.writeULong(value.requestTimestamp)
            stream.writeULong(value.timestamp)
        }

        override fun deserialize(stream: Source): ConnectionRequestAccepted {
            stream.readUByte() // Packet ID
            return ConnectionRequestAccepted(
                clientAddress = Address.deserialize(stream),
                systemIndex = stream.readUShort(),
                systemAddresses = generateSequence { if (stream.remaining > 16) Address.deserialize(stream) else null }.toList(),
                requestTimestamp = stream.readULong(),
                timestamp = stream.readULong()
            )
        }
    }
}
