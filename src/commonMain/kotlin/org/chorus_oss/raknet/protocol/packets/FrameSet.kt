package org.chorus_oss.raknet.protocol.packets

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readUByte
import kotlinx.io.writeUByte
import org.chorus_oss.raknet.protocol.RakCodec
import org.chorus_oss.raknet.protocol.types.Frame
import org.chorus_oss.raknet.protocol.types.UMedium
import org.chorus_oss.raknet.types.RakConstants
import org.chorus_oss.raknet.types.RakFlags
import org.chorus_oss.raknet.utils.hasFlag

data class FrameSet(
    val sequence: UInt,
    val frames: List<Frame>,
    val continuousSend: Boolean,
    val needsBAndAS: Boolean,
    val isPair: Boolean,
) {
    var sent: Instant = Clock.System.now()
    var resend: Instant = Instant.DISTANT_PAST

    val size: Int
        get() = frames.fold(RakConstants.DGRAM_HEADER_SIZE.toInt()) { acc, f -> acc + f.size }

    companion object : RakCodec<FrameSet> {
        override fun serialize(value: FrameSet, stream: Sink) {
            var flags = RakFlags.VALID
            if (value.continuousSend) flags = flags or RakFlags.CONTINUOUS_SEND
            if (value.needsBAndAS) flags = flags or RakFlags.NEEDS_B_AND_AS
            if (value.isPair) flags = flags or RakFlags.PAIR

            stream.writeUByte(flags)
            UMedium.serialize(value.sequence, stream)
            Frame.serialize(value.frames, stream)
        }

        override fun deserialize(stream: Source): FrameSet {
            val flags = stream.readUByte()
            return FrameSet(
                sequence = UMedium.deserialize(stream),
                frames = Frame.deserialize(stream),
                continuousSend = flags hasFlag RakFlags.CONTINUOUS_SEND,
                needsBAndAS = flags hasFlag RakFlags.NEEDS_B_AND_AS,
                isPair = flags hasFlag RakFlags.PAIR,
            )
        }
    }
}
