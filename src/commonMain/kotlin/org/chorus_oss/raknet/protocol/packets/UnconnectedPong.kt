package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import kotlinx.io.bytestring.ByteString
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.types.RakConstants
import org.chorus_oss.raknet.types.RakPacketID

data class UnconnectedPong(
    val timestamp: ULong,
    val guid: ULong,
    val magic: ByteString,
    val message: ByteString,
) {
    companion object : RakPacketCodec<UnconnectedPong> {
        override val id: UByte
            get() = RakPacketID.UNCONNECTED_PONG

        override fun serialize(value: UnconnectedPong, stream: Sink) {
            stream.writeUByte(id) // Packet ID
            stream.writeULong(value.timestamp)
            stream.writeULong(value.guid)
            stream.write(value.magic)
            stream.writeShort(value.message.size.toShort())
            stream.write(value.message)
        }

        override fun deserialize(stream: Source): UnconnectedPong {
            stream.readUByte() // Packet ID
            return UnconnectedPong(
                timestamp = stream.readULong(),
                guid = stream.readULong(),
                magic = stream.readByteString(RakConstants.MAGIC.size),
                message = stream.readByteString(stream.readUShort().toInt())
            )
        }
    }
}
