package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import kotlinx.io.bytestring.ByteString
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.protocol.types.MTU
import org.chorus_oss.raknet.protocol.types.Magic
import org.chorus_oss.raknet.types.RakPacketID

data class OpenConnectionRequest1(
    val magic: ByteString,
    val protocol: UByte,
    val mtu: UShort,
) {
    companion object : RakPacketCodec<OpenConnectionRequest1> {
        override val id: UByte
            get() = RakPacketID.OPEN_CONNECTION_REQUEST_1

        override fun serialize(value: OpenConnectionRequest1, stream: Sink) {
            stream.writeUByte(id) // Packet ID
            Magic.serialize(value.magic, stream)
            stream.writeUByte(value.protocol)
            MTU.serialize(value.mtu, stream)
        }

        override fun deserialize(stream: Source): OpenConnectionRequest1 {
            stream.readUByte() // Packet ID
            return OpenConnectionRequest1(
                magic = Magic.deserialize(stream),
                protocol = stream.readUByte(),
                mtu = MTU.deserialize(stream),
            )
        }
    }
}
