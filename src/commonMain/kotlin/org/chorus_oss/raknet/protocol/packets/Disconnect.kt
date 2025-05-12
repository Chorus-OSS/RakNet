package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.Sink
import kotlinx.io.Source
import org.chorus_oss.raknet.protocol.RakPacket
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.types.RakPacketID

class Disconnect : RakPacket(id) {
    companion object : RakPacketCodec<Disconnect> {
        override val id: UByte
            get() = RakPacketID.DISCONNECT

        override fun serialize(value: Disconnect, stream: Sink) = Unit

        override fun deserialize(stream: Source): Disconnect = Disconnect()
    }
}