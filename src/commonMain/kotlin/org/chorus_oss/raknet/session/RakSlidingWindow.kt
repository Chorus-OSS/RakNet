package org.chorus_oss.raknet.session

import kotlinx.datetime.Instant
import org.chorus_oss.raknet.protocol.packets.FrameSet
import org.chorus_oss.raknet.types.RakConstants
import kotlin.math.max
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.times

class RakSlidingWindow(private val mtu: UShort) {
    private var congestionWindow: Double = mtu.toDouble()
    private var slowStartThreshold: Double = 0.0
    private var roundTripTimeEstimate: Duration = Duration.INFINITE
    private var roundTripTimeDeviation: Duration = Duration.INFINITE
    private var congestionRecoverySequence: UInt = 0u
    private var congestionRecovery: Boolean = false
    private var bytesNotAcknowledged: Int = 0

    val transmissionBandwidth: Int
        get() = max(0, congestionWindow.toInt() - bytesNotAcknowledged)

    val retransmissionBandwidth: Int
        get() = bytesNotAcknowledged

    val retransmissionTimeOut: Duration
        get() {
            if (roundTripTimeEstimate.isInfinite()) {
                return RakConstants.CC_MAX_THRESHOLD.milliseconds
            }

            val threshold =
                2.0 * roundTripTimeEstimate + 4.0 * roundTripTimeDeviation + RakConstants.CC_ADDITIONAL_VARIANCE.milliseconds
            return threshold.coerceAtMost(RakConstants.CC_MAX_THRESHOLD.milliseconds)
        }

    val roundTripTime: Duration
        get() = roundTripTimeEstimate

    val slowStart: Boolean
        get() = congestionWindow <= slowStartThreshold || slowStartThreshold == 0.0

    fun resent(lastSequence: UInt) {
        if (congestionRecovery && congestionWindow > mtu.toDouble() * 2.0) {
            slowStartThreshold = max(congestionWindow * 0.5, mtu.toDouble())
            congestionWindow = mtu.toDouble()

            congestionRecoverySequence = lastSequence
            congestionRecovery = false
        }
    }

    fun nacked() {
        if (congestionRecovery) {
            slowStartThreshold = congestionWindow * 0.75
        }
    }

    fun acked(time: Instant, frame: FrameSet, lastSequence: UInt) {
        val roundTripTime = time - frame.sent
        bytesNotAcknowledged -= frame.size

        if (roundTripTimeEstimate.isInfinite()) {
            roundTripTimeEstimate = roundTripTime
            roundTripTimeDeviation = roundTripTime
        } else {
            val d = 0.05
            val diff = roundTripTime - roundTripTimeEstimate
            roundTripTimeEstimate += d * diff
            roundTripTimeDeviation += d * (diff.absoluteValue - roundTripTimeDeviation)
        }

        val congestionRecoveryPeriod = frame.sequence > congestionRecoverySequence

        if (congestionRecoveryPeriod) {
            congestionRecovery = true
            congestionRecoverySequence = lastSequence
        }

        if (slowStart) {
            congestionWindow += mtu.toDouble()

            if (congestionWindow > slowStartThreshold && slowStartThreshold != 0.0) {
                congestionWindow = slowStartThreshold + mtu.toDouble().pow(2) / congestionWindow
            }
        } else if (congestionRecoveryPeriod) {
            congestionWindow += mtu.toDouble().pow(2) / congestionWindow
        }
    }

    fun sent(frame: FrameSet) {
        bytesNotAcknowledged += frame.size
    }
}