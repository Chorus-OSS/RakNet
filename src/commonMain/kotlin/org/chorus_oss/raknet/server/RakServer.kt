package org.chorus_oss.raknet.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import kotlinx.io.Buffer
import kotlinx.io.readUByte
import org.chorus_oss.raknet.config.RakServerConfig
import org.chorus_oss.raknet.connection.RakConnection
import org.chorus_oss.raknet.protocol.packets.*
import org.chorus_oss.raknet.protocol.types.Address
import org.chorus_oss.raknet.types.*
import kotlin.coroutines.CoroutineContext

class RakServer(
    val host: String,
    val port: Int,
    val config: RakServerConfig,
    private val connectionFactory: RakConnectionFactory
) : CoroutineScope {
    override val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob() + CoroutineName("RakNetServer")

    val connections: MutableMap<SocketAddress, RakConnection> = mutableMapOf()

    val sendQueue: MutableList<Datagram> = mutableListOf()

    private val startupJob: CompletableDeferred<Unit> = CompletableDeferred()
    private val stopRequest: CompletableJob = Job()

    private val serverJob: Job = initServerJob()

    suspend fun startSuspend(wait: Boolean = false): RakServer {
        serverJob.start()
        startupJob.await()

        if (wait) {
            serverJob.join()
        }
        return this
    }

    fun start(wait: Boolean = false): RakServer = runBlocking { startSuspend(wait) }

    suspend fun stopSuspend(gracePeriod: Long = 500, timeout: Long = 500) {
        stopRequest.complete()

        val result = withTimeoutOrNull(gracePeriod) {
            serverJob.join()
            true
        }

        if (result == null) {
            serverJob.cancel()

            withTimeoutOrNull(gracePeriod - timeout) {
                serverJob.join()
            }
        }
    }

    fun stop(gracePeriod: Long = 500, timeout: Long = 500) = runBlocking { stopSuspend(gracePeriod, timeout) }

    private fun initServerJob(): Job {
        return launch(start = CoroutineStart.LAZY) {
            val socket = aSocket(selector).udp().bind(host, port)

            val acceptJob = launch {
                while (true) {
                    handle(socket.receive())

                    for (gram in sendQueue) {
                        socket.send(gram)
                    }
                    sendQueue.clear()

                    for (conn in connections.values) {
                        conn.tick()
                    }
                }
            }

            startupJob.complete(Unit)
            stopRequest.join()

            acceptJob.cancel()
        }
    }

    fun send(data: Datagram) {
        sendQueue.add(data)
    }

    private fun handle(datagram: Datagram) {
        val header = datagram.packet.peek().readUByte()

        val offline = header and RakHeader.VALID == 0u.toUByte()
        if (offline) {
            return handleOffline(datagram)
        }

        if (!offline) {
            connections[datagram.address]?.incoming(datagram.packet)
        }
    }

    private fun handleOffline(datagram: Datagram) {
        when (val header = datagram.packet.peek().readUByte()) {
            RakPacketID.UNCONNECTED_PING -> {
                val packet = UnconnectedPing.deserialize(datagram.packet)

                val pong = UnconnectedPong(
                    timestamp = packet.timestamp,
                    guid = config.guid,
                    magic = config.magic,
                    message = config.message
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

                if (packet.protocol != RakConstants.PROTOCOL) {
                    val incompatible = IncompatibleProtocol(
                        protocol = RakConstants.PROTOCOL,
                        guid = config.guid,
                        magic = config.magic,
                    )

                    log.warn { "Refusing connection from ${datagram.address} due to incompatible protocol version v${packet.protocol}, expected v${RakConstants.PROTOCOL}." }

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
                    guid = config.guid,
                    magic = config.magic,
                    security = false,
                    mtu = (packet.mtu + RakConstants.UDP_HEADER_SIZE).toUShort().coerceAtMost(config.maxMTUSize)
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

                if (packet.address.port != this.port) {
                    return log.warn { "Refusing connection from ${datagram.address} due to mismatched port." }
                }

                if (packet.mtu !in config.minMTUSize..config.maxMTUSize) {
                    return log.warn { "Refusing connection from ${datagram.address} due to invalid mtu size." }
                }

                if (this.connections.contains(datagram.address)) {
                    return log.warn { "Refusing connection from ${datagram.address} due to already established connection." }
                }

                val reply = OpenConnectionReply2(
                    guid = config.guid,
                    magic = config.magic,
                    address = Address.from(datagram.address as InetSocketAddress),
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
    }
}