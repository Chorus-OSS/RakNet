package org.chorus_oss.raknet.config

import org.chorus_oss.raknet.connection.RakConnection

class RakClientConfig {
    var onConnect: (RakConnection) -> Unit = {}
    var onDisconnect: (RakConnection) -> Unit = {}

    fun onConnect(fn: (RakConnection) -> Unit) {
        this.onConnect = fn
    }

    fun onDisconnect(fn: (RakConnection) -> Unit) {
        this.onDisconnect = fn
    }
}