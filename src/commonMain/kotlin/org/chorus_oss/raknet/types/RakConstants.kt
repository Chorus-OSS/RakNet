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
    const val TOTAL_PACKET_LIMIT: Int = 100_000

    const val SESSION_TIMEOUT_MS: Int = 10_000
    const val SESSION_STALE_MS: Int = 5_000

    const val CONNECTION_ATTEMPT_TIMEOUT_MS: Int = SESSION_TIMEOUT_MS
    const val CONNECTION_ATTEMPT_INTERVAL_MS: Int = 1_000
    const val CONNECTION_ATTEMPT_MAX: Int = 10

    const val AUTOFLUSH: Boolean = true
    const val AUTOFLUSH_INTERVAL_MS: Int = 10

    const val CC_MAX_THRESHOLD: Int = 2000
    const val CC_ADDITIONAL_VARIANCE: Int = 30
    const val CC_SYN: Int = 10

    const val MAX_QUEUED_BYTES: Int = 67_108_864

    val MAGIC: ByteString = ByteString(0, -1, -1, 0, -2, -2, -2, -2, -3, -3, -3, -3, 18, 52, 86, 120)
    val MTU_SIZES: List<UShort> = listOf(MIN_MTU_SIZE, 1200u, MAX_MTU_SIZE)
}