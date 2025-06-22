package org.chorus_oss.raknet.config

import kotlinx.io.bytestring.ByteString
import org.chorus_oss.raknet.connection.RakConnection
import org.chorus_oss.raknet.types.RakConstants
import kotlin.random.Random
import kotlin.random.nextULong

class RakServerConfig {
    var maxOrderingChannels: Int = RakConstants.MAX_ORDERING_CHANNELS
    var guid: ULong = Random.nextULong()
    var protocols: List<UByte> = listOf(RakConstants.PROTOCOL)
    var maxConnections: Int = 10
    var magic: ByteString = RakConstants.MAGIC
    var message: ByteString = ByteString()
    var maxMTUSize: UShort = RakConstants.MAX_MTU_SIZE
    var minMTUSize: UShort = RakConstants.MIN_MTU_SIZE
    var packetLimit: Int = RakConstants.PACKET_LIMIT
    var totalPacketLimit: Int = RakConstants.TOTAL_PACKET_LIMIT
    var security: Boolean = false

    var onConnect: (RakConnection) -> Unit = {}
    var onDisconnect: (RakConnection) -> Unit = {}

    fun onConnect(fn: (RakConnection) -> Unit) {
        this.onConnect = fn
    }

    fun onDisconnect(fn: (RakConnection) -> Unit) {
        this.onDisconnect = fn
    }
}