package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readUByte
import kotlinx.io.writeUByte
import org.chorus_oss.raknet.protocol.RakPacketCodec
import org.chorus_oss.raknet.protocol.types.Frame
import org.chorus_oss.raknet.protocol.types.UMedium
import org.chorus_oss.raknet.types.RakPacketID

data class FrameSet(
    val sequence: UInt,
    val frames: List<Frame>
) {
    companion object : RakPacketCodec<FrameSet> {
        override val id: UByte
            get() = RakPacketID.FRAME_SET

        override fun serialize(value: FrameSet, stream: Sink) {
            stream.writeUByte(id) // Packet ID
            UMedium.serialize(value.sequence, stream)
            Frame.serialize(value.frames, stream)
        }

        override fun deserialize(stream: Source): FrameSet {
            stream.readUByte() // Packet ID
            return FrameSet(
                sequence = UMedium.deserialize(stream),
                frames = Frame.deserialize(stream)
            )
        }
    }
}
