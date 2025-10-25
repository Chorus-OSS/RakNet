package org.chorus_oss.raknet.protocol.packets

import io.ktor.utils.io.core.*
import kotlinx.io.*
import kotlinx.io.bytestring.ByteString
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.types.RakConstants
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
            stream.write(value.magic)
            stream.writeUByte(value.protocol)
            stream.write(ByteArray(value.mtu.toInt() - stream.size))
        }

        override fun deserialize(stream: Source): OpenConnectionRequest1 {
            val mtu = stream.remaining.toUShort()
            stream.readUByte() // Packet ID
            return OpenConnectionRequest1(
                magic = stream.readByteString(RakConstants.MAGIC.size),
                protocol = stream.readUByte(),
                mtu = mtu,
            )
        }
    }
}
