package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readUByte
import kotlinx.io.writeUByte
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.types.RakPacketID

class Disconnect {
    companion object : RakPacketCodec<Disconnect> {
        override val id: UByte
            get() = RakPacketID.DISCONNECT

        override fun serialize(value: Disconnect, stream: Sink) {
            stream.writeUByte(id) // Packet ID
        }

        override fun deserialize(stream: Source): Disconnect {
            stream.readUByte() // Packet ID
            return Disconnect()
        }
    }
}