package org.chorus_oss.raknet.protocol.types

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.bytestring.ByteString
import org.chorus_oss.raknet.protocol.RakCodec

object SystemAddress : RakCodec<List<Address>> {
    override fun serialize(value: List<Address>, stream: Sink) {
        for (i in 0 until 20) {
            Address.serialize(
                value.getOrElse(i) {
                    Address(ByteString(0, 0, 0, 0), 0)
                },
                stream
            )
        }
    }

    override fun deserialize(stream: Source): List<Address> {
        return List(20) { Address.deserialize(stream) }
    }
}