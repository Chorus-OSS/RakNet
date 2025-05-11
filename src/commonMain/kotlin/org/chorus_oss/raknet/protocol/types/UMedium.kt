package org.chorus_oss.raknet.protocol.types

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readUByte
import kotlinx.io.writeUByte
import org.chorus_oss.raknet.protocol.Codec

typealias UMedium = UInt

object UMediumBE : Codec<UMedium> {
    override fun serialize(value: UMedium, stream: Sink) {
        val medium = value and 0xFFFFFFu
        stream.writeUByte((medium shr 16).toUByte())
        stream.writeUByte((medium shr 8).toUByte())
        stream.writeUByte(medium.toUByte())
    }

    override fun deserialize(stream: Source): UMedium {
        var data = 0u
        data = data or (stream.readUByte().toUInt() shl 16)
        data = data or (stream.readUByte().toUInt() shl 8)
        data = data or (stream.readUByte().toUInt())
        return data and 0xFFFFFFu
    }
}


object UMediumLE : Codec<UMedium> {
    override fun serialize(value: UMedium, stream: Sink) {
        val medium = value and 0xFFFFFFu
        stream.writeUByte(medium.toUByte())
        stream.writeUByte((medium shr 8).toUByte())
        stream.writeUByte((medium shr 16).toUByte())
    }

    override fun deserialize(stream: Source): UMedium {
        var data = 0u
        data = data or (stream.readUByte().toUInt())
        data = data or (stream.readUByte().toUInt() shl 8)
        data = data or (stream.readUByte().toUInt() shl 16)
        return data and 0xFFFFFFu
    }
}