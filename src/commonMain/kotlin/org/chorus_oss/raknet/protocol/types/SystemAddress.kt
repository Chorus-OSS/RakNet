package org.chorus_oss.raknet.protocol.types

import kotlinx.io.Sink
import kotlinx.io.Source
import org.chorus_oss.raknet.protocol.RakCodec

object SystemAddress : RakCodec<List<Address>> {
    override fun serialize(value: List<Address>, stream: Sink) {
        for (i in 0 until 20) {
            Address.serialize(
                value.getOrElse(i) {
                    Address("0.0.0.0", 0, 4u)
                },
                stream
            )
        }
    }

    override fun deserialize(stream: Source): List<Address> {
        return List(20) { Address.deserialize(stream) }
    }
}