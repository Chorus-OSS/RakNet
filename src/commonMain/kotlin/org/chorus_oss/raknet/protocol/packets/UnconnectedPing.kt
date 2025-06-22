package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import kotlinx.io.bytestring.ByteString
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.types.RakConstants
import org.chorus_oss.raknet.types.RakPacketID

data class UnconnectedPing(
    val timestamp: ULong,
    val magic: ByteString,
    val client: ULong
) {
    companion object : RakPacketCodec<UnconnectedPing> {
        override val id: UByte
            get() = RakPacketID.UNCONNECTED_PING

        override fun serialize(value: UnconnectedPing, stream: Sink) {
            stream.writeUByte(id) // Packet ID
            stream.writeULong(value.timestamp)
            stream.write(value.magic)
            stream.writeULong(value.client)
        }

        override fun deserialize(stream: Source): UnconnectedPing {
            stream.readUByte() // Packet ID
            return UnconnectedPing(
                timestamp = stream.readULong(),
                magic = stream.readByteString(RakConstants.MAGIC.size),
                client = stream.readULong()
            )
        }
    }
}
