package org.chorus_oss.raknet.config

import kotlinx.io.bytestring.ByteString
import org.chorus_oss.raknet.session.RakSession
import org.chorus_oss.raknet.types.RakConstants
import kotlin.random.Random
import kotlin.random.nextULong

class RakClientConfig : RakSessionConfig() {
    var guid: ULong = Random.nextULong()
    var magic: ByteString = RakConstants.MAGIC
    var connectionAttemptTimeout: Int = RakConstants.CONNECTION_ATTEMPT_TIMEOUT_MS
    var connectionAttemptInterval: Int = RakConstants.CONNECTION_ATTEMPT_INTERVAL_MS
    var connectionAttemptMax: Int = RakConstants.CONNECTION_ATTEMPT_MAX
    var serverGUID: ULong = 0uL
    var mtuSizes: List<UShort> = RakConstants.MTU_SIZES
    var internalAddresses: Int = 10
}