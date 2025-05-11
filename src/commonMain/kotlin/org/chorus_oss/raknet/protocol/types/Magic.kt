package org.chorus_oss.raknet.protocol.types

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray
import org.chorus_oss.raknet.protocol.Codec

val MagicBytes
    get() = listOf(
        0x00,
        0xFF.toByte(),
        0xFF.toByte(),
        0x00,
        0xFE.toByte(),
        0xFE.toByte(),
        0xFE.toByte(),
        0xFE.toByte(),
        0xFD.toByte(),
        0xFD.toByte(),
        0xFD.toByte(),
        0xFD.toByte(),
        0x12,
        0x34,
        0x56,
        0x78
    ).toByteArray()

object Magic : Codec<ByteArray> {
    override fun serialize(value: ByteArray, stream: Sink) {
        stream.write(MagicBytes)
    }

    override fun deserialize(stream: Source): ByteArray {
        return stream.readByteArray(MagicBytes.size)
    }
}