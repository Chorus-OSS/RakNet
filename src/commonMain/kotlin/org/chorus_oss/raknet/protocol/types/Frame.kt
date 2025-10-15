package org.chorus_oss.raknet.protocol.types

import io.ktor.utils.io.core.*
import kotlinx.io.*
import kotlinx.io.bytestring.ByteString
import org.chorus_oss.raknet.protocol.RakCodec
import org.chorus_oss.raknet.types.RakHeader
import org.chorus_oss.raknet.types.RakReliability
import kotlin.math.ceil

data class Frame(
    var reliability: RakReliability,
    var payload: ByteString,
    var reliableIndex: UInt = 0u,
    var sequenceIndex: UInt = 0u,
    var orderIndex: UInt = 0u,
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
            length += payload.size

            if (reliability.isReliable) {
                length += 3
            }
            if (reliability.isSequenced) {
                length += 3
            }
            if (reliability.isOrdered) {
                length += 4
            }
            if (isSplit) {
                length += 10
            }

            return length
        }

    companion object : RakCodec<List<Frame>> {
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
                    UMedium.serialize(frame.reliableIndex, stream)
                }

                if (frame.reliability.isSequenced) {
                    UMedium.serialize(frame.sequenceIndex, stream)
                }

                if (frame.reliability.isOrdered) {
                    UMedium.serialize(frame.orderIndex, stream)
                    stream.writeUByte(frame.orderChannel)
                }

                if (frame.isSplit) {
                    stream.writeUInt(frame.splitSize)
                    stream.writeUShort(frame.splitID)
                    stream.writeUInt(frame.splitIndex)
                }

                stream.write(frame.payload)
            }
        }

        override fun deserialize(stream: Source): List<Frame> {
            val frames = mutableListOf<Frame>()

            while (!stream.endOfInput) {
                val header = stream.readUByte()
                val reliability = RakReliability.entries[((header.toUInt() and 0xE0u) shr 5).toInt()]
                val split = (header and RakHeader.SPLIT) != 0u.toUByte()

                val length = ceil(stream.readUShort().toFloat() / 8f).toInt()

                var reliableIndex = 0u
                if (reliability.isReliable) {
                    reliableIndex = UMedium.deserialize(stream)
                }

                var sequenceIndex = 0u
                if (reliability.isSequenced) {
                    sequenceIndex = UMedium.deserialize(stream)
                }

                var orderIndex = 0u
                var orderChannel: UByte = 0u
                if (reliability.isOrdered) {
                    orderIndex = UMedium.deserialize(stream)
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

                val payload = stream.readByteString(length)

                frames.add(
                    Frame(
                        reliability,
                        payload,
                        reliableIndex,
                        sequenceIndex,
                        orderIndex,
                        orderChannel,
                        splitSize,
                        splitID,
                        splitIndex,
                    )
                )
            }

            return frames
        }
    }
}
