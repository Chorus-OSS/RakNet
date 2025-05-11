package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import org.chorus_oss.raknet.protocol.Packet
import org.chorus_oss.raknet.protocol.PacketCodec
import org.chorus_oss.raknet.protocol.types.Magic
import org.chorus_oss.raknet.types.PacketHeader

data class IncompatibleProtocol(
    val protocol: UByte,
    val magic: List<UByte>,
    val guid: ULong,
) : Packet(id) {
    companion object : PacketCodec<IncompatibleProtocol> {
        override val id: UByte
            get() = PacketHeader.INCOMPATIBLE_PROTOCOL_VERSION

        override fun serialize(value: IncompatibleProtocol, stream: Sink) {
            stream.writeUByte(value.protocol)
            Magic.serialize(value.magic, stream)
            stream.writeULong(value.guid)
        }

        override fun deserialize(stream: Source): IncompatibleProtocol {
            return IncompatibleProtocol(
                protocol = stream.readUByte(),
                magic = Magic.deserialize(stream),
                guid = stream.readULong(),
            )
        }
    }
}
