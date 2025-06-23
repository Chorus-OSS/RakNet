package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import kotlinx.io.bytestring.ByteString
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.types.RakConstants
import org.chorus_oss.raknet.types.RakPacketID

data class OpenConnectionReply1(
    val magic: ByteString,
    val guid: ULong,
    val cookie: Int?,
    val mtu: UShort
) {
    companion object : RakPacketCodec<OpenConnectionReply1> {
        override val id: UByte
            get() = RakPacketID.OPEN_CONNECTION_REPLY_1

        override fun serialize(value: OpenConnectionReply1, stream: Sink) {
            stream.writeUByte(id) // Packet ID
            stream.write(value.magic)
            stream.writeULong(value.guid)
            when (value.cookie != null) {
                true -> {
                    stream.writeByte(1)
                    stream.writeInt(value.cookie)
                }
                false -> stream.writeByte(0)
            }
            stream.writeUShort(value.mtu)
        }

        override fun deserialize(stream: Source): OpenConnectionReply1 {
            stream.readUByte() // Packet ID
            return OpenConnectionReply1(
                magic = stream.readByteString(RakConstants.MAGIC.size),
                guid = stream.readULong(),
                cookie = when (stream.readByte() == 1.toByte()) {
                    true -> stream.readInt()
                    false -> null
                },
                mtu = stream.readUShort()
            )
        }
    }
}
