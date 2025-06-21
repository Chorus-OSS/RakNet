package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import kotlinx.io.bytestring.ByteString
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.protocol.types.Magic
import org.chorus_oss.raknet.types.RakPacketID

data class IncompatibleProtocol(
    val protocol: UByte,
    val magic: ByteString,
    val guid: ULong,
) {
    companion object : RakPacketCodec<IncompatibleProtocol> {
        override val id: UByte
            get() = RakPacketID.INCOMPATIBLE_PROTOCOL_VERSION

        override fun serialize(value: IncompatibleProtocol, stream: Sink) {
            stream.writeUByte(id) // Packet ID
            stream.writeUByte(value.protocol)
            Magic.serialize(value.magic, stream)
            stream.writeULong(value.guid)
        }

        override fun deserialize(stream: Source): IncompatibleProtocol {
            stream.readUByte() // Packet ID
            return IncompatibleProtocol(
                protocol = stream.readUByte(),
                magic = Magic.deserialize(stream),
                guid = stream.readULong(),
            )
        }
    }
}
