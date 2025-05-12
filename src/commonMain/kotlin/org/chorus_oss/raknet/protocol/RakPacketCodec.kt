package org.chorus_oss.raknet.protocol

interface RakPacketCodec<T> : RakCodec<T> {
    val id: UByte
}