package org.chorus_oss.raknet.config

import kotlinx.io.bytestring.ByteString
import org.chorus_oss.raknet.session.RakSession
import org.chorus_oss.raknet.types.RakConstants
import kotlin.random.Random
import kotlin.random.nextULong

class RakClientConfig : RakSessionConfig() {
    var guid: ULong = Random.nextULong()
    var magic: ByteString = RakConstants.MAGIC
    var connectTimeout: Int = RakConstants.CONNECT_TIMEOUT_MS
    var connectRetryDelay: Int = RakConstants.CONNECT_RETRY_MS
    var connectRetryMax: Int = RakConstants.CONNECT_RETRY_MAX
    var serverGUID: ULong = 0uL
    var mtuSizes: List<UShort> = RakConstants.MTU_SIZES
    var internalAddresses: Int = 10

    var infoLogging: Boolean = false

    var onConnect: (RakSession) -> Unit = {}
    var onDisconnect: (RakSession) -> Unit = {}

    fun onConnect(fn: (RakSession) -> Unit) {
        this.onConnect = fn
    }

    fun onDisconnect(fn: (RakSession) -> Unit) {
        this.onDisconnect = fn
    }
}