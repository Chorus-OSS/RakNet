package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readULong
import kotlinx.io.writeULong
import org.chorus_oss.raknet.protocol.RakPacket
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.protocol.types.Magic
import org.chorus_oss.raknet.types.RakPacketID

data class UnconnectedPing(
    val timestamp: ULong,
    val magic: List<UByte>,
    val client: ULong
) : RakPacket(id) {
    companion object : RakPacketCodec<UnconnectedPing> {
        override val id: UByte
            get() = RakPacketID.UNCONNECTED_PING

        override fun serialize(value: UnconnectedPing, stream: Sink) {
            stream.writeULong(value.timestamp)
            Magic.serialize(value.magic, stream)
            stream.writeULong(value.client)
        }

        override fun deserialize(stream: Source): UnconnectedPing {
            return UnconnectedPing(
                timestamp = stream.readULong(),
                magic = Magic.deserialize(stream),
                client = stream.readULong()
            )
        }
    }
}
