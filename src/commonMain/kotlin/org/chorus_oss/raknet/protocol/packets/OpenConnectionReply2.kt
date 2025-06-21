package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import kotlinx.io.bytestring.ByteString
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.protocol.types.Address
import org.chorus_oss.raknet.protocol.types.Magic
import org.chorus_oss.raknet.types.RakPacketID

data class OpenConnectionReply2(
    val magic: ByteString,
    val guid: ULong,
    val address: Address,
    val mtu: UShort,
    val encryption: Boolean
) {
    companion object : RakPacketCodec<OpenConnectionReply2> {
        override val id: UByte
            get() = RakPacketID.OPEN_CONNECTION_REPLY_2

        override fun serialize(value: OpenConnectionReply2, stream: Sink) {
            stream.writeUByte(id) // Packet ID
            Magic.serialize(value.magic, stream)
            stream.writeULong(value.guid)
            Address.serialize(value.address, stream)
            stream.writeUShort(value.mtu)
            stream.writeUByte(if (value.encryption) 1u else 0u)
        }

        override fun deserialize(stream: Source): OpenConnectionReply2 {
            stream.readUByte() // Packet ID
            return OpenConnectionReply2(
                magic = Magic.deserialize(stream),
                guid = stream.readULong(),
                address = Address.deserialize(stream),
                mtu = stream.readUShort(),
                encryption = stream.readUByte() ==  1u.toUByte()
            )
        }
    }
}
