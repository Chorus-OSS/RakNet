package org.chorus_oss.raknet.client

import io.ktor.network.sockets.*
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.channels.SendChannel
import kotlinx.datetime.Clock
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.bytestring.ByteString
import org.chorus_oss.raknet.protocol.packets.ConnectionRequest
import org.chorus_oss.raknet.protocol.packets.ConnectionRequestAccepted
import org.chorus_oss.raknet.protocol.packets.NewIncomingConnection
import org.chorus_oss.raknet.protocol.types.Address
import org.chorus_oss.raknet.session.RakSession
import kotlin.coroutines.CoroutineContext

class RakClientSession(
    context: CoroutineContext,
    outbound: SendChannel<Datagram>,
    address: InetSocketAddress,
    guid: ULong,
    mtu: UShort,
) : RakSession(
    context + CoroutineName("RakClientSession"),
    outbound,
    address,
    guid,
    mtu,
) {
    override fun handle(stream: Source) {
        TODO("Not yet implemented")
    }

    override fun onConnect() {
        TODO("Not yet implemented")
    }

    override fun onDisconnect() {
        TODO("Not yet implemented")
    }

    private fun sendConnectionRequest() {
        val time = Clock.System.now().toEpochMilliseconds().toULong()

        val packet = ConnectionRequest(guid, time)
        outbound.trySend(
            Datagram(
                packet = Buffer().also { ConnectionRequest.serialize(packet, it) },
                address = address
            )
        )
    }

    private fun onConnectionRequestAccepted(stream: Source) {
        val packet = ConnectionRequestAccepted.deserialize(stream)

        sendNewIncomingConnection(packet.timestamp)
    }

    private fun sendNewIncomingConnection(time: ULong) {
        val packet = NewIncomingConnection(
            Address.from(address),
            List(10) { Address(ByteString(0, 0, 0, 0), 0) },
            time,
            Clock.System.now().toEpochMilliseconds().toULong()
        )
        outbound.trySend(
            Datagram(
                packet = Buffer().also { NewIncomingConnection.serialize(packet, it) },
                address = address
            )
        )
    }
}