package org.chorus_oss.raknet.protocol.types

import io.ktor.utils.io.core.*
import kotlinx.io.Sink
import kotlinx.io.Source
import org.chorus_oss.raknet.protocol.RakCodec

object MTU : RakCodec<UShort> {
    override fun serialize(value: UShort, stream: Sink) {
        stream.write(ByteArray(value.toInt() - stream.size))
    }

    override fun deserialize(stream: Source): UShort {
        return stream.remaining.toUShort()
    }
}