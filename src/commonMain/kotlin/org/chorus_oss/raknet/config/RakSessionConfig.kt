package org.chorus_oss.raknet.config

import kotlinx.io.Source
import org.chorus_oss.raknet.session.RakSession
import org.chorus_oss.raknet.types.RakConstants
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

open class RakSessionConfig {
    var orderingChannels: Int = RakConstants.MAX_ORDERING_CHANNELS
    var autoflush: Boolean = RakConstants.AUTOFLUSH
    var autoflushInterval: Duration = RakConstants.AUTOFLUSH_INTERVAL_MS.milliseconds
    var maxQueuedBytes: Int = RakConstants.MAX_QUEUED_BYTES

    var onInbound: RakSession.(stream: Source) -> Unit = {}

    fun onInbound(fn: RakSession.(stream: Source) -> Unit) {
        this.onInbound = fn
    }

    var onConnect: RakSession.() -> Unit = {}
    var onDisconnect: RakSession.() -> Unit = {}
}