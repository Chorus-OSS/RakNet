package org.chorus_oss.raknet.server

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.io.readUByte
import org.chorus_oss.raknet.connection.Connection
import org.chorus_oss.raknet.types.HeaderFlags
import org.chorus_oss.raknet.types.PacketHeader
import kotlin.coroutines.CoroutineContext

class Server : CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + SupervisorJob() + CoroutineName("RakNetServer")

    private val selector: SelectorManager = SelectorManager(Dispatchers.IO + CoroutineName("RakNetServer - SelectorManager"))

    private val bindings: MutableSet<BoundDatagramSocket> = mutableSetOf()

    private val connections: MutableMap<SocketAddress, ConnectedDatagramSocket> = mutableMapOf()

    private val incoming: Channel<Connection> = Channel(32)

    private var alive: Boolean = false

    fun start() {
        if (alive) {
            throw IllegalStateException("Server is already running!")
        }

        alive = true

        bindings.forEach {
            launch {
                while (alive) {
                    handle(it.receive())
                }
            }
        }
    }

    suspend fun bind(ip: String, port: Int) {
        bind(InetSocketAddress(ip, port))
    }

    suspend fun bind(address: SocketAddress) {
        if (alive) {
            throw IllegalStateException("Cannot bind while server is running!")
        }

        val socket = aSocket(selector)
            .udp()
            .bind(address)

        this.bindings.add(socket)
    }

    private fun handle(datagram: Datagram) {
        val header = datagram.packet.peek().readUByte()

        val offline = header and HeaderFlags.VALID == 0u.toUByte()
        if (offline) {
            return handleOffline(datagram)
        }

        val connection = connections[datagram.address]
        if (connection != null) {
            // connection stuff
        }
    }

    private fun handleOffline(datagram: Datagram) {
        val header = datagram.packet.peek().readUByte()

        when (header) {
            PacketHeader.UNCONNECTED_PING -> {

            }

            PacketHeader.OPEN_CONNECTION_REQUEST_1 -> {

            }

            PacketHeader.OPEN_CONNECTION_REQUEST_2 -> {

            }
        }
    }
}