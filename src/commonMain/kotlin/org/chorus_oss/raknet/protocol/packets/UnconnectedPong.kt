package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import kotlinx.io.bytestring.ByteString
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.protocol.types.Magic
import org.chorus_oss.raknet.protocol.types.ByteString16
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
            Magic.serialize(value.magic, stream)
            ByteString16.serialize(value.message, stream)
        }

        override fun deserialize(stream: Source): UnconnectedPong {
            stream.readUByte() // Packet ID
            return UnconnectedPong(
                timestamp = stream.readULong(),
                guid = stream.readULong(),
                magic = Magic.deserialize(stream),
                message = ByteString16.deserialize(stream)
            )
        }
    }
}
