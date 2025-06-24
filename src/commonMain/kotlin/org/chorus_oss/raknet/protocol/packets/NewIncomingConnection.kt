package org.chorus_oss.raknet.protocol.packets

import io.ktor.utils.io.core.remaining
import kotlinx.io.*
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.protocol.types.Address
import org.chorus_oss.raknet.types.RakPacketID

data class NewIncomingConnection(
    val serverAddress: Address,
    val internalAddresses: List<Address>,
    val incomingTimestamp: ULong,
    val serverTimestamp: ULong,
) {
    companion object : RakPacketCodec<NewIncomingConnection> {
        override val id: UByte
            get() = RakPacketID.NEW_INCOMING_CONNECTION

        override fun serialize(value: NewIncomingConnection, stream: Sink) {
            stream.writeUByte(id) // Packet ID
            Address.serialize(value.serverAddress, stream)
            value.internalAddresses.forEach { Address.serialize(it, stream) }
            stream.writeULong(value.incomingTimestamp)
            stream.writeULong(value.serverTimestamp)
        }

        override fun deserialize(stream: Source): NewIncomingConnection {
            stream.readUByte() // Packet ID
            return NewIncomingConnection(
                serverAddress = Address.deserialize(stream),
                internalAddresses = generateSequence { if (stream.remaining >= 16) Address.deserialize(stream) else null }.toList(),
                incomingTimestamp = stream.readULong(),
                serverTimestamp = stream.readULong()
            )
        }
    }
}
