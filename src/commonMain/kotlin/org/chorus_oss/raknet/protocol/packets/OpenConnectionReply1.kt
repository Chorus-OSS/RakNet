package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import org.chorus_oss.raknet.protocol.PacketCodec
import org.chorus_oss.raknet.protocol.types.Magic
import org.chorus_oss.raknet.types.PacketHeader

data class OpenConnectionReply1(
    val magic: List<UByte>,
    val guid: ULong,
    val security: Boolean,
    val mtu: UShort
) {
    companion object : PacketCodec<OpenConnectionReply1> {
        override val id: UByte
            get() = PacketHeader.OPEN_CONNECTION_REPLY_1

        override fun serialize(value: OpenConnectionReply1, stream: Sink) {
            Magic.serialize(value.magic, stream)
            stream.writeULong(value.guid)
            stream.writeUByte(if (value.security) 1u else 0u)
            stream.writeUShort(value.mtu)
        }

        override fun deserialize(stream: Source): OpenConnectionReply1 {
            return OpenConnectionReply1(
                magic = Magic.deserialize(stream),
                guid = stream.readULong(),
                security = stream.readUByte() == 1u.toUByte(),
                mtu = stream.readUShort()
            )
        }
    }
}
