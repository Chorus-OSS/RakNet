package org.chorus_oss.raknet.types

object HeaderFlags {
    const val VALID: UByte = 0x80u
    const val ACK: UByte = 0x40u
    const val NAK: UByte = 0x20u
    const val SPLIT: UByte = 0x10u
}