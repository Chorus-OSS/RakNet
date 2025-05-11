package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readULong
import kotlinx.io.writeULong
import org.chorus_oss.raknet.protocol.Packet
import org.chorus_oss.raknet.protocol.PacketCodec
import org.chorus_oss.raknet.protocol.types.Address
import org.chorus_oss.raknet.types.PacketHeader

data class NewIncomingConnection(
    val serverAddress: Address,
    val internalAddress: Address,
    val incomingTimestamp: ULong,
    val serverTimestamp: ULong,
) : Packet(id) {
    companion object : PacketCodec<NewIncomingConnection> {
        override val id: UByte
            get() = PacketHeader.NEW_INCOMING_CONNECTION

        override fun serialize(value: NewIncomingConnection, stream: Sink) {
            Address.serialize(value.serverAddress, stream)
            Address.serialize(value.internalAddress, stream)
            stream.writeULong(value.incomingTimestamp)
            stream.writeULong(value.serverTimestamp)
        }

        override fun deserialize(stream: Source): NewIncomingConnection {
            return NewIncomingConnection(
                serverAddress = Address.deserialize(stream),
                internalAddress = Address.deserialize(stream),
                incomingTimestamp = stream.readULong(),
                serverTimestamp = stream.readULong()
            )
        }
    }
}
