package org.chorus_oss.raknet.protocol.types

import kotlinx.io.*
import org.chorus_oss.raknet.protocol.Codec

object String16 : Codec<String> {
    override fun serialize(value: String, stream: Sink) {
        stream.writeUShort(value.length.toUShort())
        stream.write(value.encodeToByteArray())
    }

    override fun deserialize(stream: Source): String {
        val length = stream.readUShort()
        return stream.readByteArray(length.toInt()).decodeToString()
    }
}