package org.chorus_oss.raknet.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import kotlinx.io.Buffer
import kotlinx.io.readUByte
import org.chorus_oss.raknet.connection.RakConnection
import org.chorus_oss.raknet.protocol.packets.*
import org.chorus_oss.raknet.protocol.types.Address
import org.chorus_oss.raknet.protocol.types.Magic
import org.chorus_oss.raknet.types.*
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.random.nextULong

class RakServer(
    private val socket: BoundDatagramSocket,
    private val connectionFactory: RakConnectionFactory = RakConnectionFactory { server, address, guid, mtu ->
        RakConnection(server, address, guid, mtu)
    }
) : CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + SupervisorJob() + CoroutineName("RakNetServer")

    val connections: MutableMap<SocketAddress, RakConnection> = mutableMapOf()

    private var alive: Boolean = false

    private var guid = Random.nextULong()

    var maxMtuSize: UShort = Rak.MAX_MTU_SIZE
    var minMtuSize: UShort = Rak.MIN_MTU_SIZE
    var maxConnections: Int = 10

    var motd: RakMOTD = RakMOTD(
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

                for (conn in connections.values) {
                    conn.tick()
                }
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

        val offline = header and RakHeader.VALID == 0u.toUByte()
        if (offline) {
            return handleOffline(datagram)
        }

        if (!offline) {
            connections[datagram.address]?.incoming(datagram.packet)
        }
    }

    private suspend fun handleOffline(datagram: Datagram) {
        when (val header = datagram.packet.peek().readUByte()) {
            RakPacketID.UNCONNECTED_PING -> {
                val packet = UnconnectedPing.deserialize(datagram.packet)

                val pong = UnconnectedPong(
                    timestamp = packet.timestamp,
                    guid = guid,
                    magic = Magic.MagicBytes,
                    message = message ?: motd.copy(
                        playerCount = connections.size,
                        playerMax = maxConnections,
                        port = socket.localAddress.port(),
                        guid = guid,
                    ).toString()
                )

                return this.send(
                    Datagram(
                        packet = Buffer().also {
                            UnconnectedPong.serialize(pong, it)
                        },
                        address = datagram.address
                    )
                )
            }

            RakPacketID.OPEN_CONNECTION_REQUEST_1 -> {
                val packet = OpenConnectionRequest1.deserialize(datagram.packet)

                if (packet.protocol != Rak.PROTOCOL) {
                    val incompatible = IncompatibleProtocol(
                        protocol = Rak.PROTOCOL,
                        guid = this.guid,
                        magic = Magic.MagicBytes,
                    )

                    log.warn { "Refusing connection from ${datagram.address} due to incompatible protocol version v${packet.protocol}, expected v${Rak.PROTOCOL}." }

                    return this.send(
                        Datagram(
                            packet = Buffer().also {
                                IncompatibleProtocol.serialize(incompatible, it)
                            },
                            address = datagram.address
                        )
                    )
                }

                val reply = OpenConnectionReply1(
                    guid = this.guid,
                    magic = Magic.MagicBytes,
                    security = false,
                    mtu = (packet.mtu + Rak.UDP_HEADER_SIZE).toUShort().coerceAtMost(this.maxMtuSize)
                )

                return this.send(
                    Datagram(
                        packet = Buffer().also {
                            OpenConnectionReply1.serialize(reply, it)
                        },
                        address = datagram.address
                    )
                )
            }

            RakPacketID.OPEN_CONNECTION_REQUEST_2 -> {
                val packet = OpenConnectionRequest2.deserialize(datagram.packet)

                if (packet.address.port != this.socket.localAddress.port()) {
                    return log.warn { "Refusing connection from ${datagram.address} due to mismatched port." }
                }

                if (packet.mtu !in this.minMtuSize..this.maxMtuSize) {
                    return log.warn { "Refusing connection from ${datagram.address} due to invalid mtu size." }
                }

                if (this.connections.contains(datagram.address)) {
                    return log.warn { "Refusing connection from ${datagram.address} due to already established connection." }
                }

                val reply = OpenConnectionReply2(
                    guid = this.guid,
                    magic = Magic.MagicBytes,
                    address = Address(datagram.address as InetSocketAddress),
                    mtu = packet.mtu,
                    encryption = false
                )

                log.info { "Establishing connection from ${datagram.address} with mtu size of ${packet.mtu}." }

                this.connections[datagram.address] = connectionFactory.create(this, datagram.address, packet.client, packet.mtu)

                this.send(
                    Datagram(
                        packet = Buffer().also {
                            OpenConnectionReply2.serialize(reply, it)
                        },
                        address = datagram.address
                    )
                )
            }

            else -> {
                val id = header.toString(16).padStart(2, '0').uppercase()
                log.debug {
                    "Received unknown offline packet id \"0x${id}\" from ${datagram.address}."
                }
            }
        }
    }

    companion object {
        private val selector: SelectorManager = SelectorManager(Dispatchers.IO + CoroutineName("RakNetServer - SelectorManager"))

        private val log = KotlinLogging.logger {}

        suspend fun bind(hostname: String, port: Int): RakServer {
            return bind(InetSocketAddress(hostname, port))
        }

        suspend fun bind(address: SocketAddress): RakServer {
            val socket = aSocket(selector)
                .udp()
                .bind(address)

            return RakServer(socket)
        }
    }
}