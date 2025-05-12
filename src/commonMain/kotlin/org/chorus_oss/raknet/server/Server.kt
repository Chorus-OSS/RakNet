package org.chorus_oss.raknet.server

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.io.Buffer
import kotlinx.io.readUByte
import org.chorus_oss.raknet.connection.Connection
import org.chorus_oss.raknet.protocol.packets.UnconnectedPing
import org.chorus_oss.raknet.protocol.packets.UnconnectedPong
import org.chorus_oss.raknet.protocol.types.Magic
import org.chorus_oss.raknet.types.HeaderFlags
import org.chorus_oss.raknet.types.PacketHeader
import kotlin.coroutines.CoroutineContext

class Server(private val socket: BoundDatagramSocket) : CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + SupervisorJob() + CoroutineName("RakNetServer")

    private val connections: MutableMap<SocketAddress, Connection> = mutableMapOf()

    private val incoming: Channel<Connection> = Channel(32)

    private var alive: Boolean = false

    fun start() {
        if (alive) {
            throw IllegalStateException("Server is already running!")
        }

        alive = true

        launch {
            while (alive) {
                handle(socket.receive())
            }
        }
    }

    private suspend fun handle(datagram: Datagram) {
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

    private suspend fun handleOffline(datagram: Datagram) {
        val header = datagram.packet.readUByte()

        when (header) {
            PacketHeader.UNCONNECTED_PING -> {
                val ping = UnconnectedPing.deserialize(datagram.packet)

                val pong = UnconnectedPong(
                    timestamp = ping.timestamp,
                    guid = 9128391283u,
                    magic = Magic.MagicBytes,
                    message = listOf(
                        "MCPE",
                        "Raknet Server",
                        100,
                        "1.0.0",
                        0,
                        10,
                        9128391283u,
                        "RakNet Server",
                        "Survival",
                        1,
                        socket.localAddress.port(),
                        socket.localAddress.port() + 1
                    ).joinToString(separator = ";", postfix = ";")
                )

                val buffer = Buffer()
                UnconnectedPong.serialize(pong, buffer)

                val outDatagram = Datagram(packet = buffer, address = datagram.address)
                socket.send(outDatagram)
            }

            PacketHeader.OPEN_CONNECTION_REQUEST_1 -> {

            }

            PacketHeader.OPEN_CONNECTION_REQUEST_2 -> {

            }
        }
    }

    companion object {
        private val selector: SelectorManager = SelectorManager(Dispatchers.IO + CoroutineName("RakNetServer - SelectorManager"))

        suspend fun bind(ip: String, port: Int): Server {
            return bind(InetSocketAddress(ip, port))
        }

        suspend fun bind(address: SocketAddress): Server {
            val socket = aSocket(selector)
                .udp()
                .bind(address)

            return Server(socket)
        }
    }
}