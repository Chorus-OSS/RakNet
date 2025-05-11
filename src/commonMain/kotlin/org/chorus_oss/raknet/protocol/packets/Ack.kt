package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import org.chorus_oss.raknet.protocol.PacketCodec
import org.chorus_oss.raknet.protocol.types.UMedium
import org.chorus_oss.raknet.protocol.types.UMediumLE
import org.chorus_oss.raknet.types.PacketHeader

data class Ack(
    val sequences: List<UMedium>
) {
    companion object : PacketCodec<Ack> {
        override val id: UByte
            get() = PacketHeader.ACK

        override fun serialize(value: Ack, stream: Sink) {
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
            val size = stream.readUShort().toInt()

            val sequences = mutableListOf<UMedium>()
            for (i in 0 until size) {
                val single = stream.readByte() == 1.toByte()
                if (single) {
                    sequences.add(UMediumLE.deserialize(stream))
                } else {
                    val start = UMediumLE.deserialize(stream)
                    val end = UMediumLE.deserialize(stream)
                    sequences.addAll(start..end)
                }
            }

            return Ack(sequences)
        }

        private fun writeRange(start: UMedium, end: UMedium, stream: Sink) {
            if (start == end) {
                stream.writeByte(1)
                UMediumLE.serialize(start, stream)
            } else {
                stream.writeByte(0)
                UMediumLE.serialize(start, stream)
                UMediumLE.serialize(end, stream)
            }
        }
    }
}
