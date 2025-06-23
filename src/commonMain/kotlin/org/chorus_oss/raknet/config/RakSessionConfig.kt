package org.chorus_oss.raknet.config

import org.chorus_oss.raknet.types.RakConstants

open class RakSessionConfig {
    var guid: ULong = 0uL
    var mtu: UShort = RakConstants.MAX_MTU_SIZE
    var protocol: UByte = RakConstants.PROTOCOL
    var orderingChannels: Int = RakConstants.MAX_ORDERING_CHANNELS
    var timeout: Int = RakConstants.SESSION_TIMEOUT_MS
    var autoflush: Boolean = RakConstants.AUTOFLUSH
    var autoflushInterval: Int = RakConstants.AUTOFLUSH_INTERVAL_MS
    var maxQueuedBytes: Int = RakConstants.MAX_QUEUED_BYTES
}