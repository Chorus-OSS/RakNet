package org.chorus_oss.raknet.client

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.chorus_oss.raknet.config.RakClientConfig
import kotlin.coroutines.CoroutineContext

class RakClient(
    val config: RakClientConfig,
): CoroutineScope {
    override val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob() + CoroutineName("RakClient")

    private val start: CompletableJob = Job()
    private val started: CompletableJob = Job()
    private val stop: CompletableJob = Job()
    private val stopped: CompletableJob = Job()

    private val outbound: Channel<Datagram> = Channel(Channel.UNLIMITED)

    suspend fun connectSuspend(host: String, port: Int, wait: Boolean = false): RakClient {
        if (!start.complete()) throw IllegalStateException("RakClient is already connected")

        launch(CoroutineName("RakClientMain")) {
            log { "Launched" }
            try {
                val socket = aSocket(selector).udp().connect(InetSocketAddress(host, port))
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
                stopped.complete()
            }
            log { "Completed" }
        }
        started.join()

        if (wait) stopped.join()
        return this
    }

    fun connect(host: String, port: Int, wait: Boolean = false): RakClient = runBlocking { connectSuspend(host, port, wait) }

    suspend fun disconnectSuspend(timeout: Long = 5000) {
        stop.complete()

        withTimeoutOrNull(timeout) {
            stopped.join()
        } ?: run {
            log { "RakClient disconnect timed out after ${timeout}ms, forcing disconnect" }
            cancel()
        }
    }

    fun disconnect(timeout: Long = 5000) = runBlocking { disconnectSuspend(timeout) }

    private fun inbound(datagram: Datagram) {

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