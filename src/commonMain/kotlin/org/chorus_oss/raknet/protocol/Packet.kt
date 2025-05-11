package org.chorus_oss.raknet.protocol

interface Packet<T> : Codec<T> {
    val id: UByte
}