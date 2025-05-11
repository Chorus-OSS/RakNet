package org.chorus_oss.raknet.protocol

import kotlinx.io.Sink
import kotlinx.io.Source

interface Codec<T> {
    fun serialize(value: T, stream: Sink)

    fun deserialize(stream: Source): T
}