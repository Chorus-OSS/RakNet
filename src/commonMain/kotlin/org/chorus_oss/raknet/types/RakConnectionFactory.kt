package org.chorus_oss.raknet.types

import io.ktor.network.sockets.*
import org.chorus_oss.raknet.connection.RakConnection
import org.chorus_oss.raknet.server.RakServer

fun interface RakConnectionFactory {
    fun create(server: RakServer, address: SocketAddress, guid: ULong, mtu: UShort): RakConnection
}