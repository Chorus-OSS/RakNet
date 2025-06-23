package org.chorus_oss.raknet.protocol.packets

import io.ktor.utils.io.core.*
import kotlinx.io.*
import kotlinx.io.bytestring.ByteString
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.protocol.types.Address
import org.chorus_oss.raknet.types.RakConstants
import org.chorus_oss.raknet.types.RakPacketID

data class OpenConnectionRequest2(
    val magic: ByteString,
    val cookie: Int?,
    val address: Address,
    val mtu: UShort,
    val client: ULong
) {
    companion object : RakPacketCodec<OpenConnectionRequest2> {
        override val id: UByte
            get() = RakPacketID.OPEN_CONNECTION_REQUEST_2

        override fun serialize(value: OpenConnectionRequest2, stream: Sink) {
            stream.writeUByte(id) // Packet ID
            stream.write(value.magic)
            if (value.cookie != null) {
                stream.writeInt(value.cookie)
                stream.writeByte(0) // Not sure what this is for
            }
            Address.serialize(value.address, stream)
            stream.writeUShort(value.mtu)
            stream.writeULong(value.client)
        }

        override fun deserialize(stream: Source): OpenConnectionRequest2 {
            // Security:
            // - Enabled = 5
            // - Disabled = 0
            // Address:
            // - IPv4 = 7
            // - IPv6 = 29
            // Remaining = 10

            // IPv4 (& Remaining) = 17
            // IPv4 & Security (& Remaining) = 22
            // IPv6 (& Remaining) = 39
            // IPv6 & Security (& Remaining) = 44

            // If remaining size after deserializing magic is 22 or 44 bytes
            // then the client has sent a cookie (security) in the request.
            // Otherwise, if the size is 17 or 39, there is no cookie.

            stream.readUByte() // Packet ID
            return OpenConnectionRequest2(
                magic = stream.readByteString(RakConstants.MAGIC.size),
                cookie = when (stream.remaining) {
                    22L, 44L -> {
                        val cookie = stream.readInt()
                        stream.readByte() // Not sure what this is for
                        cookie
                    }

                    else -> null
                },
                address = Address.deserialize(stream),
                mtu = stream.readUShort(),
                client = stream.readULong()
            )
        }
    }
}
