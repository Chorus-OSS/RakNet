package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import kotlinx.io.bytestring.ByteString
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.protocol.types.Magic
import org.chorus_oss.raknet.types.RakPacketID

data class OpenConnectionReply1(
    val magic: ByteString,
    val guid: ULong,
    val security: Boolean,
    val mtu: UShort
) {
    companion object : RakPacketCodec<OpenConnectionReply1> {
        override val id: UByte
            get() = RakPacketID.OPEN_CONNECTION_REPLY_1

        override fun serialize(value: OpenConnectionReply1, stream: Sink) {
            stream.writeUByte(id) // Packet ID
            Magic.serialize(value.magic, stream)
            stream.writeULong(value.guid)
            stream.writeUByte(if (value.security) 1u else 0u)
            stream.writeUShort(value.mtu)
        }

        override fun deserialize(stream: Source): OpenConnectionReply1 {
            stream.readUByte() // Packet ID
            return OpenConnectionReply1(
                magic = Magic.deserialize(stream),
                guid = stream.readULong(),
                security = stream.readUByte() == 1u.toUByte(),
                mtu = stream.readUShort()
            )
        }
    }
}
