package org.chorus_oss.raknet.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.datetime.Clock
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteString
import kotlinx.io.readUByte
import org.chorus_oss.raknet.config.RakServerConfig
import org.chorus_oss.raknet.protocol.packets.*
import org.chorus_oss.raknet.protocol.types.Address
import org.chorus_oss.raknet.session.RakSession
import org.chorus_oss.raknet.session.RakSessionState
import org.chorus_oss.raknet.types.*
import kotlin.coroutines.CoroutineContext

class RakServer(
    val host: String,
    val port: Int,
    val config: RakServerConfig
) : CoroutineScope {
    override val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob() + CoroutineName("RakServer")

    private val sessions: MutableMap<SocketAddress, RakSession> = mutableMapOf()

    private val outbound: Channel<Datagram> = Channel(Channel.UNLIMITED)

    private val startup: CompletableDeferred<Unit> = CompletableDeferred()
    private val stop: CompletableJob = Job()

    private val serverJob: Job = launch(start = CoroutineStart.LAZY) {
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

    private fun handle(datagram: Datagram) {
        val header = datagram.packet.peek().readUByte()

        val offline = header and RakHeader.VALID == 0u.toUByte()
        when (offline) {
            true -> handleOffline(datagram)
            false -> sessions[datagram.address]?.inbound?.trySend(datagram.packet)
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

                this.sessions[datagram.address] = RakSession(
                    coroutineContext,
                    this.outbound,
                    datagram.address as InetSocketAddress,
                    packet.client,
                    packet.mtu
                ) {
                    fun RakSession.handleConnectionRequest(stream: Source) {
                        val request = ConnectionRequest.deserialize(stream)

                        val accepted = ConnectionRequestAccepted(
                            clientAddress = Address.from(address),
                            systemIndex = 0u,
                            systemAddresses = emptyList(),
                            requestTimestamp = request.clientTimestamp,
                            timestamp = Clock.System.now().toEpochMilliseconds().toULong()
                        )

                        send(
                            Buffer().also { ConnectionRequestAccepted.serialize(accepted, it) }.readByteString(),
                            RakReliability.ReliableOrdered,
                            RakPriority.Normal,
                        )
                    }

                    fun RakSession.handleConnectedPing(stream: Source) {
                        val ping = ConnectedPing.deserialize(stream)

                        val pong = ConnectedPong(
                            pingTimestamp = ping.timestamp,
                            timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
                        )

                        send(
                            Buffer().also { ConnectedPong.serialize(pong, it) }.readByteString(),
                            RakReliability.ReliableOrdered,
                            RakPriority.Normal,
                        )
                    }

                    onInbound { stream ->
                        stream.peek().use {
                            when (it.readUByte()) {
                                RakPacketID.DISCONNECT -> {
                                    val connected = state == RakSessionState.Connected
                                    state = RakSessionState.Disconnecting
                                    disconnect(send = false, connected = connected)
                                    state = RakSessionState.Disconnected
                                }

                                RakPacketID.CONNECTION_REQUEST -> {
                                    if (state == RakSessionState.Connecting) {
                                        handleConnectionRequest(stream)
                                    } else RakSession.log.warn { "Unexpected ConnectionRequest" }
                                }

                                RakPacketID.CONNECTED_PING -> handleConnectedPing(stream)
                                RakPacketID.NEW_INCOMING_CONNECTION -> {
                                    if (state == RakSessionState.Connecting) {
                                        state = RakSessionState.Connected
                                        onConnect(this)
                                    } else RakSession.log.warn { "Unexpected NewIncomingConnection" }
                                }

                                else -> onPacket(stream)
                            }
                        }
                    }

                    onConnect = ::onConnect
                    onDisconnect = ::onDisconnect
                }

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