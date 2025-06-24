package org.chorus_oss.raknet.client

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.sockets.*
import io.ktor.util.reflect.instanceOf
import io.ktor.utils.io.core.preview
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.channels.SendChannel
import kotlinx.datetime.Clock
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.bytestring.ByteString
import kotlinx.io.readUByte
import org.chorus_oss.raknet.protocol.packets.ConnectionRequest
import org.chorus_oss.raknet.protocol.packets.ConnectionRequestAccepted
import org.chorus_oss.raknet.protocol.packets.NewIncomingConnection
import org.chorus_oss.raknet.protocol.types.Address
import org.chorus_oss.raknet.session.RakSession
import org.chorus_oss.raknet.session.RakSessionState
import org.chorus_oss.raknet.types.RakPacketID
import org.chorus_oss.raknet.types.RakPriority
import org.chorus_oss.raknet.types.RakReliability
import kotlin.coroutines.CoroutineContext

class RakClientSession(
    context: CoroutineContext,
    outbound: SendChannel<Datagram>,
    address: InetSocketAddress,
    guid: ULong,
    mtu: UShort,
    private val onConnect: (RakSession) -> Unit,
    private val onDisconnect: (RakSession) -> Unit,
) : RakSession(
    context + CoroutineName("RakClientSession"),
    outbound,
    address,
    guid,
    mtu,
) {
    init {
        sendConnectionRequest()
    }

    override fun handle(stream: Source) {
        stream.preview {
            when (it.readUByte()) {
                RakPacketID.CONNECTION_REQUEST_ACCEPTED -> handleConnectionRequestAccepted(stream)
                RakPacketID.CONNECTION_REQUEST_FAILED -> {
                    state = RakSessionState.Disconnecting
                    disconnect(send = false, connected = false)
                    state = RakSessionState.Disconnected
                    log.warn { "Connection request failed" }
                }
            }
        }
    }

    override fun onConnect() = onConnect(this)

    override fun onDisconnect() = onDisconnect(this)

    private fun sendConnectionRequest() {
        val time = Clock.System.now().toEpochMilliseconds().toULong()

        val packet = ConnectionRequest(guid, time)
        send(
            Buffer().also { ConnectionRequest.serialize(packet, it) },
            RakReliability.ReliableOrdered,
            RakPriority.Immediate,
        )
    }

    private fun handleConnectionRequestAccepted(stream: Source) {
        val packet = ConnectionRequestAccepted.deserialize(stream)

        state = RakSessionState.Connected
        onConnect()
        sendNewIncomingConnection(packet.timestamp)
    }

    private fun sendNewIncomingConnection(time: ULong) {
        val packet = NewIncomingConnection(
            Address.from(address),
            List(10) { Address(ByteString(0, 0, 0, 0), 0) },
            time,
            Clock.System.now().toEpochMilliseconds().toULong()
        )
        send(
            Buffer().also { NewIncomingConnection.serialize(packet, it) },
            RakReliability.ReliableOrdered,
            RakPriority.Immediate,
        )
    }

    companion object {
        val log = KotlinLogging.logger { }
    }
}