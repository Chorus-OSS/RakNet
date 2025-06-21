package org.chorus_oss.raknet.types

import kotlinx.io.bytestring.ByteString

object RakConstants {
    const val PROTOCOL: UByte = 11u
    const val UDP_HEADER_SIZE: UShort = 28u
    const val MIN_MTU_SIZE: UShort = 400u
    const val MAX_MTU_SIZE: UShort = 1492u
    const val DGRAM_HEADER_SIZE: UShort = 4u
    const val DGRAM_MTU_OVERHEAD: UShort = 36u

    const val MAX_ORDERING_CHANNELS: Int = 16
    const val PACKET_LIMIT: Int = 120
    const val TOTAL_PACKET_LIMIT: Int = 100000

    val MAGIC: ByteString = ByteString(0, -1, -1, 0, -2, -2, -2, -2, -3, -3, -3, -3, 18, 52, 86, 120)
}