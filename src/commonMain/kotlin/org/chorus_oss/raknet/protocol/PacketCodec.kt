package org.chorus_oss.raknet.protocol

interface PacketCodec<T> : Codec<T> {
    val id: UByte
}