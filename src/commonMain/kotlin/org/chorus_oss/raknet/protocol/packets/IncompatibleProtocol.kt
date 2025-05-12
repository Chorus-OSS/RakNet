package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import org.chorus_oss.raknet.protocol.RakPacket
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.protocol.types.Magic
import org.chorus_oss.raknet.types.RakPacketID

data class IncompatibleProtocol(
    val protocol: UByte,
    val magic: List<UByte>,
    val guid: ULong,
) : RakPacket(id) {
    companion object : RakPacketCodec<IncompatibleProtocol> {
        override val id: UByte
            get() = RakPacketID.INCOMPATIBLE_PROTOCOL_VERSION

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
