package org.chorus_oss.raknet.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.io.Buffer
import kotlinx.io.readUByte
import org.chorus_oss.raknet.connection.Connection
import org.chorus_oss.raknet.protocol.packets.*
import org.chorus_oss.raknet.protocol.types.Address
import org.chorus_oss.raknet.protocol.types.Magic
import org.chorus_oss.raknet.types.Constants
import org.chorus_oss.raknet.types.HeaderFlags
import org.chorus_oss.raknet.types.MOTD
import org.chorus_oss.raknet.types.PacketHeader
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.random.Random
import kotlin.random.nextULong

class Server(private val socket: BoundDatagramSocket) : CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + SupervisorJob() + CoroutineName("RakNetServer")

    private val connections: MutableMap<SocketAddress, Connection> = mutableMapOf()

    private val incoming: Channel<Connection> = Channel(32)

    private var alive: Boolean = false

    private var guid = Random.nextULong()

    var maxMtuSize: UShort = Constants.MAX_MTU_SIZE
    var minMtuSize: UShort = Constants.MIN_MTU_SIZE
    var maxConnections: Int = 10

    var motd: MOTD = MOTD(
        edition = "MCPE",
        name = "Chorus/RakNet",
        protocol = 0,
        version = "1.0.0",
        playerCount = this.connections.size,
        playerMax = maxConnections,
        guid = guid,
        subName = "chorus-oss.org",
        gamemode = "Survival",
        nintendoLimited = false,
        port = socket.localAddress.port()
    )

    var message: String? = null

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

    fun stop() {
        alive = false

        socket.close()

        this.cancel()
    }

    suspend fun send(data: Datagram) {
        socket.send(data)
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
        when (val header = datagram.packet.readUByte()) {
            PacketHeader.UNCONNECTED_PING -> {
                val ping = UnconnectedPing.deserialize(datagram.packet)

                val pong = UnconnectedPong(
                    timestamp = ping.timestamp,
                    guid = guid,
                    magic = Magic.MagicBytes,
                    message = message ?: motd.copy(
                        playerCount = connections.size,
                        playerMax = maxConnections,
                        port = socket.localAddress.port(),
                        guid = guid,
                    ).toString()
                )

                val packet = Buffer()
                UnconnectedPong.serialize(pong, packet)

                return this.send(Datagram(packet, address = datagram.address))
            }

            PacketHeader.OPEN_CONNECTION_REQUEST_1 -> {
                val request = OpenConnectionRequest1.deserialize(datagram.packet)

                if (request.protocol != Constants.PROTOCOL) {
                    val incompatible = IncompatibleProtocol(
                        protocol = Constants.PROTOCOL,
                        guid = this.guid,
                        magic = Magic.MagicBytes,
                    )

                    val packet = Buffer()
                    IncompatibleProtocol.serialize(incompatible, packet)

                    log.warn { "Refusing connection from ${datagram.address} due to incompatible protocol version v${request.protocol}, expected v${Constants.PROTOCOL}." }

                    return this.send(Datagram(packet, address = datagram.address))
                }

                val reply = OpenConnectionReply1(
                    guid = this.guid,
                    magic = Magic.MagicBytes,
                    security = false,
                    mtu = (request.mtu + Constants.UDP_HEADER_SIZE).toUShort().coerceAtMost(this.maxMtuSize)
                )

                val packet = Buffer()
                OpenConnectionReply1.serialize(reply, packet)

                return this.send(Datagram(packet, address = datagram.address))
            }

            PacketHeader.OPEN_CONNECTION_REQUEST_2 -> {
                val request = OpenConnectionRequest2.deserialize(datagram.packet)

                if (request.address.port != this.socket.localAddress.port()) {
                    return log.warn { "Refusing connection from ${datagram.address} due to mismatched port." }
                }

                if (request.mtu !in this.minMtuSize..this.maxMtuSize) {
                    return log.warn { "Refusing connection from ${datagram.address} due to invalid mtu size." }
                }

                if (this.connections.contains(datagram.address)) {
                    return log.warn { "Refusing connection from ${datagram.address} due to already established connection." }
                }

                val reply = OpenConnectionReply2(
                    guid = this.guid,
                    magic = Magic.MagicBytes,
                    address = Address(datagram.address as InetSocketAddress),
                    mtu = request.mtu,
                    encryption = false
                )

                log.debug { "Establishing connection from ${datagram.address} with mtu size of ${request.mtu}." }

                this.connections[datagram.address] = Connection()

                val packet = Buffer()
                OpenConnectionReply2.serialize(reply, packet)

                this.send(Datagram(packet, address = datagram.address))
            }

            else -> {
                val id = header.toString(16).padStart(2, '0').uppercase()

                log.debug { "Received unknown offline packet id \"0x$id\" from ${datagram.address}." }
            }
        }
    }

    companion object {
        private val selector: SelectorManager = SelectorManager(Dispatchers.IO + CoroutineName("RakNetServer - SelectorManager"))

        private val log = KotlinLogging.logger {}

        suspend fun bind(hostname: String, port: Int): Server {
            return bind(InetSocketAddress(hostname, port))
        }

        suspend fun bind(address: SocketAddress): Server {
            val socket = aSocket(selector)
                .udp()
                .bind(address)

            return Server(socket)
        }
    }
}