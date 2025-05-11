package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import org.chorus_oss.raknet.protocol.Packet
import org.chorus_oss.raknet.protocol.PacketCodec
import org.chorus_oss.raknet.protocol.types.MTU
import org.chorus_oss.raknet.protocol.types.Magic
import org.chorus_oss.raknet.types.PacketHeader

data class OpenConnectionRequest1(
    val magic: List<UByte>,
    val protocol: UByte,
    val mtu: UShort,
) : Packet(id) {
    companion object : PacketCodec<OpenConnectionRequest1> {
        override val id: UByte
            get() = PacketHeader.OPEN_CONNECTION_REQUEST_1

        override fun serialize(value: OpenConnectionRequest1, stream: Sink) {
            Magic.serialize(value.magic, stream)
            stream.writeUByte(value.protocol)
            MTU.serialize(value.mtu, stream)
        }

        override fun deserialize(stream: Source): OpenConnectionRequest1 {
            return OpenConnectionRequest1(
                magic = Magic.deserialize(stream),
                protocol = stream.readUByte(),
                mtu = MTU.deserialize(stream),
            )
        }
    }
}
