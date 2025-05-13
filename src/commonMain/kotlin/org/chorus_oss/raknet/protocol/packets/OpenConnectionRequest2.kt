package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.protocol.types.Address
import org.chorus_oss.raknet.protocol.types.Magic
import org.chorus_oss.raknet.types.RakPacketID

data class OpenConnectionRequest2(
    val magic: List<UByte>,
    val address: Address,
    val mtu: UShort,
    val client: ULong
) {
    companion object : RakPacketCodec<OpenConnectionRequest2> {
        override val id: UByte
            get() = RakPacketID.OPEN_CONNECTION_REQUEST_2

        override fun serialize(value: OpenConnectionRequest2, stream: Sink) {
            stream.writeUByte(id) // Packet ID
            Magic.serialize(value.magic, stream)
            Address.serialize(value.address, stream)
            stream.writeUShort(value.mtu)
            stream.writeULong(value.client)
        }

        override fun deserialize(stream: Source): OpenConnectionRequest2 {
            stream.readUByte() // Packet ID
            return OpenConnectionRequest2(
                magic = Magic.deserialize(stream),
                address = Address.deserialize(stream),
                mtu = stream.readUShort(),
                client = stream.readULong()
            )
        }
    }
}
