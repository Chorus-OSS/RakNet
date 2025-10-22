package org.chorus_oss.raknet.protocol.packets

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.io.*
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.session.RakSession
import org.chorus_oss.raknet.types.RakPacketID

data class ConnectedPong(
    val pingTimestamp: ULong,
    val timestamp: ULong,
) {
    companion object : RakPacketCodec<ConnectedPong> {
        override val id: UByte
            get() = RakPacketID.CONNECTED_PONG

        override fun serialize(value: ConnectedPong, stream: Sink) {
            stream.writeUByte(id) // Packet ID
            stream.writeULong(value.pingTimestamp)
            stream.writeULong(value.timestamp)
        }

        override fun deserialize(stream: Source): ConnectedPong {
            stream.readUByte() // Packet ID
            return ConnectedPong(
                pingTimestamp = stream.readULong(),
                timestamp = stream.readULong(),
            )
        }

        fun RakSession.handleConnectedPong(stream: Source) {
            val pong = deserialize(stream)

            val ping = Instant.fromEpochMilliseconds(pong.timestamp.toLong())
            if (this.currPing == ping) {
                this.lastPing = this.currPing
                this.lastPong = Clock.System.now()
            }
        }
    }
}
