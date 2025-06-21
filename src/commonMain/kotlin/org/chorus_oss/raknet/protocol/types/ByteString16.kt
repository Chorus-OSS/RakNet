package org.chorus_oss.raknet.protocol.types

import kotlinx.io.*
import kotlinx.io.bytestring.ByteString
import org.chorus_oss.raknet.protocol.RakCodec

object ByteString16 : RakCodec<ByteString> {
    override fun serialize(value: ByteString, stream: Sink) {
        stream.writeUShort(value.size.toUShort())
        stream.write(value)
    }

    override fun deserialize(stream: Source): ByteString {
        val length = stream.readUShort()
        return stream.readByteString(length.toInt())
    }
}