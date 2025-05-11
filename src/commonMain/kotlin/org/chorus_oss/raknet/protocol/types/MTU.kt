package org.chorus_oss.raknet.protocol.types

import io.ktor.utils.io.core.*
import kotlinx.io.Sink
import kotlinx.io.Source
import org.chorus_oss.raknet.protocol.Codec

object MTU : Codec<Int> {
    override fun serialize(value: Int, stream: Sink) {
        stream.write(ByteArray(value - stream.size))
    }

    override fun deserialize(stream: Source): Int {
        return stream.remaining.toInt()
    }
}