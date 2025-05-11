package org.chorus_oss.raknet.protocol.types

import kotlinx.io.*
import org.chorus_oss.raknet.protocol.Codec

object Magic : Codec<List<UByte>> {
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

    override fun serialize(value: List<UByte>, stream: Sink) {
        for (byte in MagicBytes) {
            stream.writeUByte(byte)
        }
    }

    override fun deserialize(stream: Source): List<UByte> {
        return List(MagicBytes.size) {
            stream.readUByte()
        }
    }
}