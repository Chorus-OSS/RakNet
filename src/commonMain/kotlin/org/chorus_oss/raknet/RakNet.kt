package org.chorus_oss.raknet

import org.chorus_oss.raknet.config.RakServerConfig
import org.chorus_oss.raknet.server.RakServer
import org.chorus_oss.raknet.types.RakConnectionFactory

fun rakServer(
    host: String = "0.0.0.0",
    port: Int = 19132,
    connectionFactory: RakConnectionFactory = RakConnectionFactory(),
    config: RakServerConfig.() -> Unit = {}
): RakServer {
    val config = RakServerConfig().apply(config)

    return RakServer(host, port, config, connectionFactory)
}