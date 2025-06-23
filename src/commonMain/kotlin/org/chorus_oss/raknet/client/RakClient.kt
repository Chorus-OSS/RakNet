package org.chorus_oss.raknet.client

import kotlinx.coroutines.runBlocking
import org.chorus_oss.raknet.config.RakClientConfig

class RakClient(
    val config: RakClientConfig,
) {
    suspend fun connectSuspend(host: String, port: Int, wait: Boolean = false): RakClient {
        return this
    }

    fun connect(host: String, port: Int, wait: Boolean = false): RakClient = runBlocking { connectSuspend(host, port, wait) }

    suspend fun disconnectSuspend() {

    }

    fun disconnect() = runBlocking { disconnectSuspend() }
}