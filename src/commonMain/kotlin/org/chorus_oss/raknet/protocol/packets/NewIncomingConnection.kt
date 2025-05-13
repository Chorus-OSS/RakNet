package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.protocol.types.Address
import org.chorus_oss.raknet.types.RakPacketID

data class NewIncomingConnection(
    val serverAddress: Address,
    val internalAddress: Address,
    val incomingTimestamp: ULong,
    val serverTimestamp: ULong,
) {
    companion object : RakPacketCodec<NewIncomingConnection> {
        override val id: UByte
            get() = RakPacketID.NEW_INCOMING_CONNECTION

        override fun serialize(value: NewIncomingConnection, stream: Sink) {
            stream.writeUByte(id) // Packet ID
            Address.serialize(value.serverAddress, stream)
            Address.serialize(value.internalAddress, stream)
            stream.writeULong(value.incomingTimestamp)
            stream.writeULong(value.serverTimestamp)
        }

        override fun deserialize(stream: Source): NewIncomingConnection {
            stream.readUByte() // Packet ID
            return NewIncomingConnection(
                serverAddress = Address.deserialize(stream),
                internalAddress = Address.deserialize(stream),
                incomingTimestamp = stream.readULong(),
                serverTimestamp = stream.readULong()
            )
        }
    }
}
