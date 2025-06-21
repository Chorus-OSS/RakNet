package org.chorus_oss.raknet.protocol.types

import kotlinx.io.*
import kotlinx.io.bytestring.ByteString
import org.chorus_oss.raknet.protocol.RakCodec

object Magic : RakCodec<ByteString> {
    val MagicBytes
        get() = listOf<UByte>(
            0x00u,
            0xFFu,
            0xFFu,
            0x00u,
            0xFEu,
            0xFEu,
            0xFEu,
            0xFEu,
            0xFDu,
            0xFDu,
            0xFDu,
            0xFDu,
            0x12u,
            0x34u,
            0x56u,
            0x78u
        )

    override fun serialize(value: ByteString, stream: Sink) {
        stream.write(value)
    }

    override fun deserialize(stream: Source): ByteString {
        return stream.readByteString(MagicBytes.size)
    }
}