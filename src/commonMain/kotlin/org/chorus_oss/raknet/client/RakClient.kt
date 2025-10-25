package org.chorus_oss.raknet.client

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.datetime.Clock
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.bytestring.ByteString
import kotlinx.io.readByteString
import kotlinx.io.readUByte
import org.chorus_oss.raknet.config.RakClientConfig
import org.chorus_oss.raknet.protocol.packets.*
import org.chorus_oss.raknet.protocol.types.Address
import org.chorus_oss.raknet.session.RakSession
import org.chorus_oss.raknet.session.RakSessionState
import org.chorus_oss.raknet.types.RakConstants
import org.chorus_oss.raknet.types.RakPacketID
import org.chorus_oss.raknet.types.RakPriority
import org.chorus_oss.raknet.types.RakReliability
import org.chorus_oss.raknet.utils.overhead
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

class RakClient(
    val host: String,
    val port: Int,
    val config: RakClientConfig,
) : CoroutineScope {
    override val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob() + CoroutineName("RakClient")

    private val start: CompletableJob = Job()
    private val started: CompletableJob = Job()
    private val stop: CompletableJob = Job()
    private val stopped: CompletableJob = Job()

    private val remote: InetSocketAddress = InetSocketAddress(host, port)

    private val outbound: Channel<Datagram> = Channel(Channel.UNLIMITED)

    private var state: RakClientState = RakClientState.Handshake1
    private var attempts: Int = 0
    private var cookie: Int? = null

    private var session: RakSession? = null

    private val timeout: Job = launch(CoroutineName("RakClientTimeout"), start = CoroutineStart.LAZY) {
        delay(config.connectionAttemptTimeout.milliseconds)
        log.warn { "RakClient connection timed out after ${config.timeout}ms" }
        stop()
    }

    private val request: Job = launch(CoroutineName("RakClientRequest"), start = CoroutineStart.LAZY) {
        loop@ while (attempts < config.connectionAttemptMax && isActive) {
            when (state) {
                RakClientState.Handshake1 -> {
                    sendOpenConnectionRequest1()
                    attempts++
                }

                RakClientState.Handshake2 -> {
                    sendOpenConnectionRequest2()
                }

                RakClientState.HandshakeCompleted -> break@loop
            }
            delay(config.connectionAttemptInterval.milliseconds)
        }

        if (attempts >= config.connectionAttemptMax) {
            log.warn { "RakClient connection failed after $attempts attempts" }
            stop()
        }
    }

    suspend fun startSuspend(wait: Boolean = false): RakClient {
        if (!start.complete()) throw IllegalStateException("RakClient has already started")

        launch(CoroutineName("RakClientMain")) {
            try {
                val socket = aSocket(selector).udp().connect(remote)
                log.debug { "RakClient connected to $host:$port" }
                try {
                    launch(CoroutineName("RakClientInbound")) {
                        while (isActive) {
                            val datagram = socket.incoming.receiveCatching().getOrNull() ?: break
                            handle(datagram)
                        }
                    }

                    launch(CoroutineName("RakClientOutbound")) {
                        while (isActive) {
                            val datagram = outbound.receiveCatching().getOrNull() ?: break
                            socket.send(datagram)
                        }
                    }

                    timeout.start()
                    request.start()

                    started.complete()
                    stop.join()
                } finally {
                    socket.close()

                    timeout.cancel()
                    request.cancel()
                }
            } catch (e: Exception) {
                started.completeExceptionally(e)
                throw e
            } finally {
                outbound.close()

                stopped.complete()
            }
        }
        started.join()

        if (wait) stopped.join()
        return this
    }

    fun start(wait: Boolean = false): RakClient = runBlocking { startSuspend(wait) }

    suspend fun stopSuspend(timeout: Long = 5000) {
        stop.complete()

        withTimeoutOrNull(timeout) {
            stopped.join()
        } ?: run {
            log.warn { "RakClient closing timed out after ${timeout}ms, force-closing" }
            cancel()
        }
    }

    fun stop(timeout: Long = 5000) = runBlocking { stopSuspend(timeout) }

    private fun handle(datagram: Datagram) {
        if (state == RakClientState.HandshakeCompleted) {
            session?.inbound?.trySend(datagram.packet)
            return
        }

        datagram.packet.peek().use {
            val id = it.readUByte()
            when (id) {
                RakPacketID.OPEN_CONNECTION_REPLY_1 -> handleOpenConnectionReply1(datagram.packet)
                RakPacketID.OPEN_CONNECTION_REPLY_2 -> handleOpenConnectionReply2(datagram.packet)
                RakPacketID.INCOMPATIBLE_PROTOCOL_VERSION -> {
                    log.warn { "RakClient connection failed due to incompatible protocol version" }
                    stop()
                }

                RakPacketID.ALREADY_CONNECTED -> {
                    log.warn { "RakClient connection failed because this IP is already connected" }
                    stop()
                }

                RakPacketID.NO_FREE_INCOMING_CONNECTIONS -> {
                    log.warn { "RakClient connection failed because the server has no free connections" }
                    stop()
                }

                RakPacketID.IP_RECENTLY_CONNECTED -> {
                    log.warn { "RakClient connection failed because this IP recently connected" }
                    stop()
                }
            }
        }
    }

    private fun onSuccess() {
        timeout.cancel()
        request.cancel()

        log.info { "Establishing connection to $remote with mtu size of ${config.mtu}" }

        val session = RakSession(
            coroutineContext,
            outbound,
            remote,
            config.guid,
            config.mtu
        ) {
            fun RakSession.sendNewIncomingConnection(time: ULong) {
                val packet = NewIncomingConnection(
                    Address.from(address),
                    List(10) { Address(ByteString(0, 0, 0, 0), 0) },
                    time,
                    Clock.System.now().toEpochMilliseconds().toULong()
                )
                send(
                    Buffer().also { NewIncomingConnection.serialize(packet, it) }.readByteString(),
                    RakReliability.ReliableOrdered,
                    RakPriority.Immediate,
                )
            }

            fun RakSession.handleConnectionRequestAccepted(stream: Source) {
                val packet = ConnectionRequestAccepted.deserialize(stream)

                state = RakSessionState.Connected
                sendNewIncomingConnection(packet.timestamp)
                config.onConnect(this)
            }

            onInbound { stream ->
                stream.peek().use {
                    when (it.readUByte()) {
                        RakPacketID.CONNECTION_REQUEST_ACCEPTED -> handleConnectionRequestAccepted(stream)
                        RakPacketID.CONNECTION_REQUEST_FAILED -> {
                            disconnect()
                            RakSession.log.warn { "Connection request failed" }
                        }

                        else -> onPacket(stream)
                    }
                }
            }

            onConnect = ::onConnect
            onDisconnect = ::onDisconnect
        }

        val time = Clock.System.now().toEpochMilliseconds().toULong()

        val packet = ConnectionRequest(session.guid, time)
        session.send(
            Buffer().also { ConnectionRequest.serialize(packet, it) }.readByteString(),
            RakReliability.ReliableOrdered,
            RakPriority.Immediate,
        )

        this.session = session
    }

    private fun sendOpenConnectionRequest1() {
        val mtu =
            (config.mtuSizes[(attempts / 4).coerceAtLeast(config.mtuSizes.size - 1)] - remote.overhead - RakConstants.UDP_HEADER_SIZE).toUShort()
        val magic = config.magic
        val protocol = config.protocol

        val packet = OpenConnectionRequest1(magic, protocol, mtu)
        outbound.trySend(
            Datagram(
                packet = Buffer().also { OpenConnectionRequest1.serialize(packet, it) },
                address = remote
            )
        )
    }

    private fun handleOpenConnectionReply1(stream: Source) {
        val packet = OpenConnectionReply1.deserialize(stream)

        config.mtu = packet.mtu
        config.serverGUID = packet.guid

        state = RakClientState.Handshake2
        sendOpenConnectionRequest2()
    }

    private fun sendOpenConnectionRequest2() {
        val mtu = config.mtu
        val magic = config.magic
        val guid = config.guid

        val packet = OpenConnectionRequest2(magic, cookie, Address.from(remote), mtu, guid)
        outbound.trySend(
            Datagram(
                packet = Buffer().also { OpenConnectionRequest2.serialize(packet, it) },
                address = remote
            )
        )
    }

    private fun handleOpenConnectionReply2(stream: Source) {
        val packet = OpenConnectionReply2.deserialize(stream)

        if (packet.encryption) {
            log.warn { "RakClient failed to connect, security exception" }
            stop()
        } else {
            config.mtu = packet.mtu

            state = RakClientState.HandshakeCompleted
            onSuccess()
        }
    }

    private fun onConnect(session: RakSession) {
        config.onConnect(session)
    }

    private fun onDisconnect(session: RakSession) {
        config.onDisconnect(session)
        stop()
    }

    companion object {
        private val selector: SelectorManager =
            SelectorManager(Dispatchers.IO + CoroutineName("RakClient - SelectorManager"))

        private val log = KotlinLogging.logger {}
    }
}