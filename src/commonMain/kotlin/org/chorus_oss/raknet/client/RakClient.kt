package org.chorus_oss.raknet.client

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.utils.io.core.preview
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readUByte
import org.chorus_oss.raknet.config.RakClientConfig
import org.chorus_oss.raknet.protocol.packets.OpenConnectionReply1
import org.chorus_oss.raknet.protocol.packets.OpenConnectionReply2
import org.chorus_oss.raknet.protocol.packets.OpenConnectionRequest1
import org.chorus_oss.raknet.protocol.packets.OpenConnectionRequest2
import org.chorus_oss.raknet.protocol.types.Address
import org.chorus_oss.raknet.types.RakPacketID
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

class RakClient(
    val host: String,
    val port: Int,
    val config: RakClientConfig,
): CoroutineScope {
    override val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob() + CoroutineName("RakClient")

    private val start: CompletableJob = Job()
    private val started: CompletableJob = Job()
    private val stop: CompletableJob = Job()
    private val stopped: CompletableJob = Job()

    private val remote: InetSocketAddress = InetSocketAddress(host, port)

    private val outbound: Channel<Datagram> = Channel(Channel.UNLIMITED)

    private var state: RakOfflineState = RakOfflineState.Handshake1
    private var attempts: Int = 0
    private var cookie: Int? = null

    private val timeout: Job = launch(start = CoroutineStart.LAZY) {
        delay(config.timeout.milliseconds)
        log { "RakClient connection timed out after ${config.timeout}ms"}
        stop()
    }

    private val request: Job = launch(start = CoroutineStart.LAZY) {
        loop@while (attempts <= config.connectRetryMax) {
            when (state) {
                RakOfflineState.Handshake1 -> {
                    sendOpenConnectionRequest1()
                    attempts++
                }
                RakOfflineState.Handshake2 -> {
                    sendOpenConnectionRequest2()
                }
                RakOfflineState.HandshakeCompleted -> break@loop
            }
            delay(config.connectRetryDelay.milliseconds)
        }

        if (attempts >= config.connectRetryMax) {
            log { "RakClient connection failed after $attempts attempts" }
            stop()
        }
    }

    suspend fun startSuspend(wait: Boolean = false): RakClient {
        if (!start.complete()) throw IllegalStateException("RakClient has already started")

        launch(CoroutineName("RakClientMain")) {
            log { "Launched" }
            try {
                val socket = aSocket(selector).udp().connect(remote)
                log { "RakClient connected to $host:$port" }
                try {
                    launch(CoroutineName("RakClientInbound")) {
                        log { "Launched" }
                        while (isActive) {
                            val datagram = socket.incoming.receiveCatching().getOrNull() ?: break
                            inbound(datagram)
                        }
                        log { "Completed" }
                    }

                    launch(CoroutineName("RakClientOutbound")) {
                        log { "Launched" }
                        while (isActive) {
                            val datagram = outbound.receiveCatching().getOrNull() ?: break
                            socket.send(datagram)
                        }
                        log { "Completed" }
                    }

                    started.complete()
                    stop.join()
                } finally {
                    socket.close()
                }
            } catch (e: Exception) {
                started.completeExceptionally(e)
                throw e
            } finally {
                outbound.close()
                timeout.cancel()
                request.cancel()
                stopped.complete()
            }
            log { "Completed" }
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
            log { "RakClient closing timed out after ${timeout}ms, force-closing" }
            cancel()
        }
    }

    fun stop(timeout: Long = 5000) = runBlocking { stopSuspend(timeout) }

    private fun inbound(datagram: Datagram) {
        if (state == RakOfflineState.HandshakeCompleted) {
            // onPacket
            return
        }

        datagram.packet.preview {
            val id = it.readUByte()
            when (id) {
                RakPacketID.OPEN_CONNECTION_REPLY_1 -> onOpenConnectionReply1(datagram.packet)
                RakPacketID.OPEN_CONNECTION_REPLY_2 -> onOpenConnectionReply2(datagram.packet)
                RakPacketID.INCOMPATIBLE_PROTOCOL_VERSION -> {
                    log { "RakClient connection failed due to incompatible protocol version" }
                    stop()
                }
                RakPacketID.ALREADY_CONNECTED -> {
                    log { "RakClient connection failed because this IP is already connected" }
                    stop()
                }
                RakPacketID.NO_FREE_INCOMING_CONNECTIONS -> {
                    log { "RakClient connection failed because the server has no free connections" }
                    stop()
                }
                RakPacketID.IP_RECENTLY_CONNECTED -> {
                    log { "RakClient connection failed because the IP recent connected" }
                    stop()
                }
            }
        }
    }

    private fun onConnect() {
        timeout.cancel()
        request.cancel()
    }

    private fun sendOpenConnectionRequest1() {
        val mtu = config.mtuSizes[(attempts / 4).coerceAtLeast(config.mtuSizes.size - 1)]
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

    private fun onOpenConnectionReply1(stream: Source) {
        val packet = OpenConnectionReply1.deserialize(stream)

        config.mtu = packet.mtu
        config.serverGUID = packet.guid

        state = RakOfflineState.Handshake2
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

    private fun onOpenConnectionReply2(stream: Source) {
        val packet = OpenConnectionReply2.deserialize(stream)

        if (packet.encryption) {
            log { "RakClient failed to connect, security exception" }
            stop()
        } else {
            config.mtu = packet.mtu

            state = RakOfflineState.HandshakeCompleted
        }
    }

    private fun log(fn: () -> String) {
        if (config.infoLogging) log.info(fn)
        else log.debug(fn)
    }

    companion object {
        private val selector: SelectorManager = SelectorManager(Dispatchers.IO + CoroutineName("RakClient - SelectorManager"))

        private val log = KotlinLogging.logger {}
    }
}