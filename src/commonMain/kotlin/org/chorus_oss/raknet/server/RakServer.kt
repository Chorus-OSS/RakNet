package org.chorus_oss.raknet.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.io.Buffer
import kotlinx.io.readUByte
import org.chorus_oss.raknet.config.RakServerConfig
import org.chorus_oss.raknet.protocol.packets.*
import org.chorus_oss.raknet.protocol.types.Address
import org.chorus_oss.raknet.session.RakSession
import org.chorus_oss.raknet.types.RakConstants
import org.chorus_oss.raknet.types.RakHeader
import org.chorus_oss.raknet.types.RakPacketID
import kotlin.coroutines.CoroutineContext

class RakServer(
    val host: String,
    val port: Int,
    val config: RakServerConfig
) : CoroutineScope {
    override val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob() + CoroutineName("RakServer")

    private val sessions: MutableMap<SocketAddress, RakServerSession> = mutableMapOf()

    private val outbound: Channel<Datagram> = Channel(Channel.UNLIMITED)

    private val startup: CompletableDeferred<Unit> = CompletableDeferred()
    private val stop: CompletableJob = Job()

    private val serverJob: Job = initServerJob()

    suspend fun startSuspend(wait: Boolean = false): RakServer {
        serverJob.start()
        startup.await()

        if (wait) {
            serverJob.join()
        }
        return this
    }

    fun start(wait: Boolean = false): RakServer = runBlocking { startSuspend(wait) }

    suspend fun stopSuspend() {
        stop.complete()

        serverJob.cancelAndJoin()
    }

    fun stop() = runBlocking { stopSuspend() }

    private fun initServerJob(): Job {
        return launch(start = CoroutineStart.LAZY) {
            val socket = aSocket(selector).udp().bind(host, port)

            val acceptJob = launch {
                while (isActive) {
                    handle(socket.receive())
                }
            }

            val sendJob = launch {
                for (data in outbound) {
                    socket.send(data)
                }
            }

            startup.complete(Unit)
            stop.join()

            acceptJob.cancel()
            sendJob.cancel()
        }
    }

    private suspend fun handle(datagram: Datagram) {
        val header = datagram.packet.peek().readUByte()

        val offline = header and RakHeader.VALID == 0u.toUByte()
        if (offline) {
            return handleOffline(datagram)
        }

        if (!offline) {
            sessions[datagram.address]?.inbound(datagram.packet)
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

                outbound.trySend(
                    Datagram(
                        packet = Buffer().also {
                            UnconnectedPong.serialize(pong, it)
                        },
                        address = datagram.address
                    )
                )
                return
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

                    outbound.trySend(
                        Datagram(
                            packet = Buffer().also {
                                IncompatibleProtocol.serialize(incompatible, it)
                            },
                            address = datagram.address
                        )
                    )
                    return
                }

                val reply = OpenConnectionReply1(
                    guid = config.guid,
                    magic = config.magic,
                    cookie = null,
                    mtu = (packet.mtu + RakConstants.UDP_HEADER_SIZE).toUShort().coerceAtMost(config.maxMTUSize)
                )

                outbound.trySend(
                    Datagram(
                        packet = Buffer().also {
                            OpenConnectionReply1.serialize(reply, it)
                        },
                        address = datagram.address
                    )
                )
                return
            }

            RakPacketID.OPEN_CONNECTION_REQUEST_2 -> {
                val packet = OpenConnectionRequest2.deserialize(datagram.packet)

                if (packet.address.port != this.port) {
                    return log.warn { "Refusing connection from ${datagram.address} due to mismatched port." }
                }

                if (packet.mtu !in config.minMTUSize..config.maxMTUSize) {
                    return log.warn { "Refusing connection from ${datagram.address} due to invalid mtu size." }
                }

                if (this.sessions.contains(datagram.address)) {
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

                this.sessions[datagram.address] = RakServerSession(
                    coroutineContext,
                    this.outbound,
                    datagram.address as InetSocketAddress,
                    packet.client,
                    packet.mtu,
                    ::onDisconnect,
                    ::onConnect
                )

                outbound.trySend(
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

    private fun onDisconnect(session: RakSession) {
        config.onDisconnect(session)
        sessions.remove(session.address)
    }

    private fun onConnect(session: RakSession) {
        config.onConnect(session)
    }

    companion object {
        private val selector: SelectorManager =
            SelectorManager(Dispatchers.IO + CoroutineName("RakServer - SelectorManager"))

        private val log = KotlinLogging.logger {}
    }
}