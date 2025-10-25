package org.chorus_oss.raknet.utils

import io.ktor.network.sockets.*

val SocketAddress.overhead: UShort
    get() = (this as? InetSocketAddress)?.resolveAddress()?.let {
        when (it.size) {
            4 -> 20u
            16 -> 40u
            else -> null
        }
    } ?: throw IllegalArgumentException()