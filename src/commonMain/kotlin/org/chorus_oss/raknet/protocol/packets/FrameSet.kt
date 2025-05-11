package org.chorus_oss.raknet.protocol.packets

import kotlinx.io.*
import org.chorus_oss.raknet.protocol.Packet
import org.chorus_oss.raknet.protocol.PacketCodec
import org.chorus_oss.raknet.protocol.types.Frame
import org.chorus_oss.raknet.types.PacketHeader

data class FrameSet(
    val sequence: UInt,
    val frames: List<Frame>
) : Packet(id) {
    companion object : PacketCodec<FrameSet> {
        override val id: UByte
            get() = PacketHeader.FRAME_SET

        override fun serialize(value: FrameSet, stream: Sink) {
            stream.writeUIntLe(value.sequence)
            Frame.serialize(value.frames, stream)
        }

        override fun deserialize(stream: Source): FrameSet {
            return FrameSet(
                sequence = stream.readUIntLe(),
                frames = Frame.deserialize(stream)
            )
        }
    }
}
