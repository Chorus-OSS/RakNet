package org.chorus_oss.raknet.protocol

import org.chorus_oss.raknet.protocol.packets.*

open class Packet(val id: UByte) {
    companion object {
        val registry: Map<UByte, Codec<*>> = mapOf(
            Ack.id to Ack,
            ConnectedPing.id to ConnectedPing,
            ConnectedPong.id to ConnectedPong,
            ConnectionRequest.id to ConnectionRequest,
            ConnectionRequestAccepted.id to ConnectionRequestAccepted,
            Disconnect.id to Disconnect,
            FrameSet.id to FrameSet,
            IncompatibleProtocol.id to IncompatibleProtocol,
            NAck.id to NAck,
            NewIncomingConnection.id to NewIncomingConnection,
            OpenConnectionReply1.id to OpenConnectionReply1,
            OpenConnectionReply2.id to OpenConnectionReply2,
            OpenConnectionRequest1.id to OpenConnectionRequest1,
            OpenConnectionRequest2.id to OpenConnectionRequest2,
            UnconnectedPing.id to UnconnectedPing,
            UnconnectedPong.id to UnconnectedPong,
        )
    }
}