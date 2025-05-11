package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.Sink
import kotlinx.io.Source
import org.chorus_oss.raknet.protocol.Packet
import org.chorus_oss.raknet.protocol.PacketCodec
import org.chorus_oss.raknet.types.PacketHeader

class Disconnect : Packet(id) {
    companion object : PacketCodec<Disconnect> {
        override val id: UByte
            get() = PacketHeader.DISCONNECT

        override fun serialize(value: Disconnect, stream: Sink) = Unit

        override fun deserialize(stream: Source): Disconnect = Disconnect()
    }
}