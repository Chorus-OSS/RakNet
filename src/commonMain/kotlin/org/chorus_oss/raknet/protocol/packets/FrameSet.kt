package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.protocol.types.Frame
import org.chorus_oss.raknet.protocol.types.UMedium
import org.chorus_oss.raknet.protocol.types.UMediumLE
import org.chorus_oss.raknet.types.RakPacketID

data class FrameSet(
    val sequence: UMedium,
    val frames: List<Frame>
) {
    companion object : RakPacketCodec<FrameSet> {
        override val id: UByte
            get() = RakPacketID.FRAME_SET

        override fun serialize(value: FrameSet, stream: Sink) {
            stream.writeUByte(id) // Packet ID
            UMediumLE.serialize(value.sequence, stream)
            Frame.serialize(value.frames, stream)
        }

        override fun deserialize(stream: Source): FrameSet {
            stream.readUByte() // Packet ID
            return FrameSet(
                sequence = UMediumLE.deserialize(stream),
                frames = Frame.deserialize(stream)
            )
        }
    }
}
