package org.chorus_oss.raknet.types

import io.ktor.network.sockets.*
import org.chorus_oss.raknet.connection.RakConnection
import org.chorus_oss.raknet.server.RakServer

open class RakConnectionFactory {
    open fun create(server: RakServer, address: SocketAddress, guid: ULong, mtu: UShort): RakConnection {
        return RakConnection(server, address, guid, mtu)
    }
}