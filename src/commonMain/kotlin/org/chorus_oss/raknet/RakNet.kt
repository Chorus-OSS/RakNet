package org.chorus_oss.raknet

import org.chorus_oss.raknet.client.RakClient
import org.chorus_oss.raknet.config.RakClientConfig
import org.chorus_oss.raknet.config.RakServerConfig
import org.chorus_oss.raknet.server.RakServer

fun rakServer(
    host: String = "0.0.0.0",
    port: Int = 19132,
    config: RakServerConfig.() -> Unit = {}
): RakServer {
    val config = RakServerConfig().apply(config)

    return RakServer(host, port, config)
}

fun rakClient(
    host: String = "127.0.0.1",
    port: Int = 19132,
    config: RakClientConfig.() -> Unit = {}
): RakClient {
    val config = RakClientConfig().apply(config)

    return RakClient(host, port, config)
}