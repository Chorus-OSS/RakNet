package org.chorus_oss.raknet.protocol.types

import io.ktor.network.sockets.*
import kotlinx.io.*
import kotlinx.io.bytestring.ByteString
import org.chorus_oss.raknet.protocol.RakCodec

data class Address(
    val address: ByteString,
    val port: Int
) {
    init {
        require(address.size == 4 || address.size == 16) { "Address must have 4 bytes for IPv4 or 16 bytes for IPv6" }
    }

    val isIPv6: Boolean = address.size == 16
    val version: UByte = if (isIPv6) 6u else 4u

    companion object : RakCodec<Address> {
        fun from(socketAddress: InetSocketAddress): Address {
            val resolvedAddress = requireNotNull(socketAddress.resolveAddress()) {
                "Address could not be resolved for $socketAddress"
            }

            return Address(
                address = ByteString(resolvedAddress),
                port = socketAddress.port
            )
        }

        override fun serialize(value: Address, stream: Sink) {
            stream.writeUByte(value.version)

            when (value.isIPv6) {
                false -> {
                    stream.write(value.address)
                    stream.writeUShort(value.port.toUShort())
                }

                true -> {
                    stream.writeUShort(23u)
                    stream.writeUShort(value.port.toUShort())
                    stream.writeUInt(0u)
                    stream.write(value.address)
                    stream.writeUInt(0u)
                }
            }
        }

        override fun deserialize(stream: Source): Address {
            return when (val version = stream.readUByte()) {
                4u.toUByte() -> {
                    val address = stream.readByteString(4)
                    val port = stream.readUShort().toInt()
                    Address(address, port)
                }

                6u.toUByte() -> {
                    stream.skip(2)
                    val port = stream.readUShort().toInt()
                    stream.skip(4)
                    val address = stream.readByteString(16)
                    stream.skip(4)
                    Address(address, port)
                }

                else -> throw IllegalArgumentException("Unexpected version: $version")
            }
        }
    }
}
