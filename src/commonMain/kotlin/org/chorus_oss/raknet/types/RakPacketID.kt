package org.chorus_oss.raknet.types

object RakPacketID {
    const val CONNECTED_PING: UByte = 0x00u
    const val UNCONNECTED_PING: UByte = 0x01u
    const val CONNECTED_PONG: UByte = 0x03u
    const val OPEN_CONNECTION_REQUEST_1: UByte = 0x05u
    const val OPEN_CONNECTION_REPLY_1: UByte = 0x06u
    const val OPEN_CONNECTION_REQUEST_2: UByte = 0x07u
    const val OPEN_CONNECTION_REPLY_2: UByte = 0x08u
    const val CONNECTION_REQUEST: UByte = 0x09u
    const val CONNECTION_REQUEST_ACCEPTED: UByte = 0x10u
    const val CONNECTION_REQUEST_FAILED: UByte = 0x11u
    const val ALREADY_CONNECTED: UByte = 0x12u
    const val NEW_INCOMING_CONNECTION: UByte = 0x13u
    const val NO_FREE_INCOMING_CONNECTIONS: UByte = 0x14u
    const val DISCONNECT: UByte = 0x15u
    const val INCOMPATIBLE_PROTOCOL_VERSION: UByte = 0x19u
    const val IP_RECENTLY_CONNECTED: UByte = 0x1au
    const val UNCONNECTED_PONG: UByte = 0x1Cu
}