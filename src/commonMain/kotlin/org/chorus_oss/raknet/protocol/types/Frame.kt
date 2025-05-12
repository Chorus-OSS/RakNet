package org.chorus_oss.raknet.protocol.types

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.utils.io.core.*
import kotlinx.io.*
import kotlinx.io.Buffer
import org.chorus_oss.raknet.protocol.RakCodec
import org.chorus_oss.raknet.types.RakHeader
import org.chorus_oss.raknet.types.RakReliability
import kotlin.math.ceil

data class Frame(
    var reliability: RakReliability,
    var payload: Buffer,
    var reliableIndex: UMedium = 0u,
    var sequenceIndex: UMedium = 0u,
    var orderIndex: UMedium = 0u,
    var orderChannel: UByte = 0u,
    var splitSize: UInt = 0u,
    var splitID: UShort = 0u,
    var splitIndex: UInt = 0u
) {
    val isSplit: Boolean
        get() = splitSize > 0u

    val byteLength: Long
        get() {
            var length: Long = 0

            length += 3
            length += payload.remaining

            if (reliability.isReliable) { length += 3 }
            if (reliability.isSequenced) { length += 3 }
            if (reliability.isOrdered) { length += 4 }
            if (isSplit) { length += 10 }

            return length
        }

    companion object : RakCodec<List<Frame>> {
        private val log = KotlinLogging.logger {}

        override fun serialize(value: List<Frame>, stream: Sink) {
            for (frame in value) {
                stream.writeUByte(
                    (frame.reliability.ordinal shl 5).toUByte() or (
                        when {
                            frame.isSplit -> RakHeader.SPLIT
                            else -> 0u
                        }
                    )
                )

                stream.writeUShort((frame.payload.size shl 3).toUShort())

                if (frame.reliability.isReliable) {
                    UMediumLE.serialize(frame.reliableIndex, stream)
                }

                if (frame.reliability.isSequenced) {
                    UMediumLE.serialize(frame.sequenceIndex, stream)
                }

                if (frame.reliability.isOrdered) {
                    UMediumLE.serialize(frame.orderIndex, stream)
                    stream.writeUByte(frame.orderChannel)
                }

                if (frame.isSplit) {
                    stream.writeUInt(frame.splitSize)
                    stream.writeUShort(frame.splitID)
                    stream.writeUInt(frame.splitIndex)
                }

                stream.write(frame.payload, frame.payload.size)
            }
        }

        override fun deserialize(stream: Source): List<Frame> {
            val frames = mutableListOf<Frame>()

            do {
                val header = stream.readUByte()
                val reliability = RakReliability.entries[((header.toUInt() and 0xE0u) shr 5).toInt()]
                val split = (header and RakHeader.SPLIT) != 0u.toUByte()

                val length = ceil(stream.readUShort().toFloat() / 8f).toLong()

                var reliableIndex: UMedium = 0u
                if (reliability.isReliable) {
                    reliableIndex = UMediumLE.deserialize(stream)
                }

                var sequenceIndex: UMedium = 0u
                if (reliability.isSequenced) {
                    sequenceIndex = UMediumLE.deserialize(stream)
                }

                var orderIndex: UMedium = 0u
                var orderChannel: UByte = 0u
                if (reliability.isOrdered) {
                    orderIndex = UMediumLE.deserialize(stream)
                    orderChannel = stream.readUByte()
                }

                var splitSize = 0u
                var splitID: UShort = 0u
                var splitIndex = 0u
                if (split) {
                    splitSize = stream.readUInt()
                    splitID = stream.readUShort()
                    splitIndex = stream.readUInt()
                }

                val payload = Buffer()
                stream.readTo(payload, length)

                frames.add(Frame(
                    reliability,
                    payload,
                    reliableIndex,
                    sequenceIndex,
                    orderIndex,
                    orderChannel,
                    splitSize,
                    splitID,
                    splitIndex,
                ))

            } while (!stream.endOfInput)

            return frames
        }
    }
}
