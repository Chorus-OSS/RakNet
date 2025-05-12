package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readULong
import kotlinx.io.writeULong
import org.chorus_oss.raknet.protocol.RakPacket
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.protocol.types.Magic
import org.chorus_oss.raknet.protocol.types.String16
import org.chorus_oss.raknet.types.RakPacketID

data class UnconnectedPong(
    val timestamp: ULong,
    val guid: ULong,
    val magic: List<UByte>,
    val message: String,
) : RakPacket(id) {
    companion object : RakPacketCodec<UnconnectedPong> {
        override val id: UByte
            get() = RakPacketID.UNCONNECTED_PONG

        override fun serialize(value: UnconnectedPong, stream: Sink) {
            stream.writeULong(value.timestamp)
            stream.writeULong(value.guid)
            Magic.serialize(value.magic, stream)
            String16.serialize(value.message, stream)
        }

        override fun deserialize(stream: Source): UnconnectedPong {
            return UnconnectedPong(
                timestamp = stream.readULong(),
                guid = stream.readULong(),
                magic = Magic.deserialize(stream),
                message = String16.deserialize(stream)
            )
        }
    }
}
