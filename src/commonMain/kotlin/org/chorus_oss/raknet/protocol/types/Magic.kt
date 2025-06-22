package org.chorus_oss.raknet.protocol.types

import kotlinx.io.*
import kotlinx.io.bytestring.ByteString
import org.chorus_oss.raknet.protocol.RakCodec
import org.chorus_oss.raknet.types.RakConstants

object Magic : RakCodec<ByteString> {
    override fun serialize(value: ByteString, stream: Sink) {
        stream.write(value)
    }

    override fun deserialize(stream: Source): ByteString {
        return stream.readByteString(RakConstants.MAGIC.size)
    }
}