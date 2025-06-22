package org.chorus_oss.raknet.protocol.types

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readUByte
import kotlinx.io.writeUByte
import org.chorus_oss.raknet.protocol.RakCodec

object UMedium : RakCodec<UInt> {
    override fun serialize(value: UInt, stream: Sink) {
        val medium = value and 0xFFFFFFu
        stream.writeUByte(medium.toUByte())
        stream.writeUByte((medium shr 8).toUByte())
        stream.writeUByte((medium shr 16).toUByte())
    }

    override fun deserialize(stream: Source): UInt {
        var data = 0u
        data = data or (stream.readUByte().toUInt())
        data = data or (stream.readUByte().toUInt() shl 8)
        data = data or (stream.readUByte().toUInt() shl 16)
        return data and 0xFFFFFFu
    }
}