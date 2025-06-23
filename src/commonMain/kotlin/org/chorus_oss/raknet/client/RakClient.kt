package org.chorus_oss.raknet.client

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.chorus_oss.raknet.config.RakClientConfig
import kotlin.coroutines.CoroutineContext

class RakClient(
    val config: RakClientConfig,
): CoroutineScope {
    override val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob() + CoroutineName("RakClient")

    private var host: String = "127.0.0.1"
    private var port = 19132

    private val clientJob: Job = Job()

    suspend fun connectSuspend(host: String, port: Int, wait: Boolean = false): RakClient {
        this.host = host
        this.port = port

        return this
    }

    fun connect(host: String, port: Int, wait: Boolean = false): RakClient = runBlocking { connectSuspend(host, port, wait) }

    suspend fun disconnectSuspend() {

    }

    fun disconnect() = runBlocking { disconnectSuspend() }

    private fun initClientJob(): Job {
        return launch(start = CoroutineStart.LAZY) {
            val socket = aSocket(selector).udp().connect(InetSocketAddress(host, port))
        }
    }

    companion object {
        private val selector: SelectorManager = SelectorManager(Dispatchers.IO + CoroutineName("RakClient - SelectorManager"))

        private val log = KotlinLogging.logger {}
    }
}