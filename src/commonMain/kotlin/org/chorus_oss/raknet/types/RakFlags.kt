package org.chorus_oss.raknet.types

object RakFlags {
    const val VALID: UByte = 0x80u
    const val ACK: UByte = 0x40u
    const val HAS_B_AND_AS: UByte = 0x20u
    const val NACK: UByte = 0x20u
    const val PAIR: UByte = 0x10u
    const val SPLIT: UByte = 0x10u
    const val CONTINUOUS_SEND: UByte = 0x08u
    const val NEEDS_B_AND_AS: UByte = 0x04u
}