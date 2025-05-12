package org.chorus_oss.raknet.types

object Rak {
    const val PROTOCOL: UByte = 11u
    const val UDP_HEADER_SIZE: UShort = 28u
    const val MIN_MTU_SIZE: UShort = 400u
    const val MAX_MTU_SIZE: UShort = 1492u
    const val DGRAM_HEADER_SIZE: UShort = 4u
    const val DGRAM_MTU_OVERHEAD: UShort = 36u
}