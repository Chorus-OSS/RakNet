package org.chorus_oss.raknet.protocol.types

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.io.*
import kotlinx.io.Buffer

data class Address(
    val address: String,
    val port: Int,
    val version: UByte,
) {
    constructor(socketAddress: InetSocketAddress) : this(
        address = socketAddress.hostname,
        port = socketAddress.port,
        version = when {
            socketAddress.hostname.contains(".") -> 4u
            socketAddress.hostname.contains(":") -> 6u
            else -> {
                log.error { "Invalid socketAddress: $socketAddress" }
                0u
            }
        }
    )


    companion object {
        val log = KotlinLogging.logger {}

        fun serialize(value: Address, stream: Buffer) {
            stream.writeUByte(value.version)

            when (value.version) {
                4u.toUByte() -> {
                    val bits = value.address.split(".", limit = 4)
                    for (bit in bits) {
                        stream.writeUByte(bit.toUByte(10))
                    }
                    stream.writeUShort(value.port.toUShort())
                }

                6u.toUByte() -> {
                    stream.writeUShort(23u)
                    stream.writeUShort(value.port.toUShort())
                    stream.writeUInt(0u)
                    val parts = value.address.split(":")
                    for (part in parts) {
                        stream.writeUShort(part.toUShort(16))
                    }
                    stream.writeUInt(0u)
                }

                else -> log.error { "Unexpected version: ${value.version}" }
            }
        }
    }

    fun deserialize(stream: Buffer): Address {
        return when (val version = stream.readUByte()) {
            4u.toUByte() -> {
                val bytes = stream.readBytes(4)
                val address = bytes.joinToString { it.toString(10) }
                val port = stream.readUShort().toInt()
                Address(address, port, version)
            }

            6u.toUByte() -> {
                stream.skip(2)
                val port = stream.readUShort().toInt()
                stream.skip(4)
                val address = List(8) {
                    stream.readUShort().toString(16).padStart(4, '0')
                }.joinToString(separator = ":")
                stream.skip(4)
                Address(address, port, version)
            }

            else -> {
                log.error { "Unexpected version: $version" }
                Address("", 0, 0u)
            }
        }
    }
}
