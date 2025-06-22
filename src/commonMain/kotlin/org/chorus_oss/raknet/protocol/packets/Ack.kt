package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.protocol.types.UMedium
import org.chorus_oss.raknet.types.RakPacketID

data class Ack(
    val sequences: List<UInt>
) {
    companion object : RakPacketCodec<Ack> {
        override val id: UByte
            get() = RakPacketID.ACK

        override fun serialize(value: Ack, stream: Sink) {
            stream.writeUByte(id) // Packet ID

            val sorted = value.sequences.sorted()

            val buffer = Buffer()
            var count: UShort = 0u

            var start = sorted[0]
            var end = start
            for (i in sorted) {
                if (i == end + 1u) {
                    end = i
                } else {
                    writeRange(start, end, buffer)
                    count++
                    start = i
                    end = i
                }
            }

            writeRange(start, end, buffer)
            count++

            stream.writeUShort(count)
            stream.write(buffer.readByteArray())
        }

        override fun deserialize(stream: Source): Ack {
            stream.readUByte() // Packet ID

            val size = stream.readUShort().toInt()

            val sequences = mutableListOf<UInt>()
            for (i in 0 until size) {
                val single = stream.readByte() == 1.toByte()
                if (single) {
                    sequences.add(UMedium.deserialize(stream))
                } else {
                    val start = UMedium.deserialize(stream)
                    val end = UMedium.deserialize(stream)
                    sequences.addAll(start..end)
                }
            }

            return Ack(sequences)
        }

        private fun writeRange(start: UInt, end: UInt, stream: Sink) {
            if (start == end) {
                stream.writeByte(1)
                UMedium.serialize(start, stream)
            } else {
                stream.writeByte(0)
                UMedium.serialize(start, stream)
                UMedium.serialize(end, stream)
            }
        }
    }
}
