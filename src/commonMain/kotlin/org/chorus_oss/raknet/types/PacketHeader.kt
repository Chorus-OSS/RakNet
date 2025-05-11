package org.chorus_oss.raknet.types

object PacketHeader {
    const val CONNECTED_PING: UByte = 0x00u
    const val UNCONNECTED_PING: UByte = 0x01u
    const val CONNECTED_PONG: UByte = 0x03u
    const val OPEN_CONNECTION_REQUEST_1: UByte = 0x05u
    const val OPEN_CONNECTION_REPLY_1: UByte = 0x06u
    const val OPEN_CONNECTION_REQUEST_2: UByte = 0x07u
    const val OPEN_CONNECTION_REPLY_2: UByte = 0x08u
    const val CONNECTION_REQUEST: UByte = 0x09u
    const val CONNECTION_REQUEST_ACCEPTED: UByte = 0x10u
    const val NEW_INCOMING_CONNECTION: UByte = 0x13u
    const val DISCONNECT: UByte = 0x15u
    const val INCOMPATIBLE_PROTOCOL_VERSION: UByte = 0x19u
    const val UNCONNECTED_PONG: UByte = 0x1Cu
    const val FRAME_SET: UByte = 0x80u
    const val NACK: UByte = 0xA0u
    const val ACK: UByte = 0xC0u
}