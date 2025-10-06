package org.chorus_oss.raknet.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.channels.SendChannel
import kotlinx.datetime.Clock
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteString
import kotlinx.io.readUByte
import org.chorus_oss.raknet.protocol.packets.ConnectedPing
import org.chorus_oss.raknet.protocol.packets.ConnectedPong
import org.chorus_oss.raknet.protocol.packets.ConnectionRequest
import org.chorus_oss.raknet.protocol.packets.ConnectionRequestAccepted
import org.chorus_oss.raknet.protocol.types.Address
import org.chorus_oss.raknet.protocol.types.Frame
import org.chorus_oss.raknet.session.RakSession
import org.chorus_oss.raknet.session.RakSessionState
import org.chorus_oss.raknet.types.RakPacketID
import org.chorus_oss.raknet.types.RakPriority
import org.chorus_oss.raknet.types.RakReliability
import kotlin.coroutines.CoroutineContext

class RakServerSession(
    context: CoroutineContext,
    outbound: SendChannel<Datagram>,
    address: InetSocketAddress,
    guid: ULong,
    mtu: UShort,
    private val onDisconnect: (RakServerSession) -> Unit,
    private val onConnect: (RakServerSession) -> Unit
) : RakSession(
    context + CoroutineName("RakServerSession"),
    outbound,
    address,
    guid,
    mtu,
) {
    override fun handle(stream: Source) {
        stream.preview {
            when (it.readUByte()) {
                RakPacketID.DISCONNECT -> {
                    val connected = state == RakSessionState.Connected
                    state = RakSessionState.Disconnecting
                    disconnect(send = false, connected = connected)
                    state = RakSessionState.Disconnected
                }

                RakPacketID.CONNECTION_REQUEST -> {
                    if (state == RakSessionState.Connecting) {
                        handleConnectionRequest(stream)
                    } else log.warn { "Unexpected ConnectionRequest" }
                }

                RakPacketID.CONNECTED_PING -> handleConnectedPing(stream)
                RakPacketID.NEW_INCOMING_CONNECTION -> {
                    if (state == RakSessionState.Connecting) {
                        state = RakSessionState.Connected
                        onConnect()
                    } else log.warn { "Unexpected NewIncomingConnection" }
                }

                else -> onPacket(stream)
            }
        }
    }

    override fun onConnect() = onConnect(this)

    override fun onDisconnect() = onDisconnect(this)

    private fun handleConnectionRequest(stream: Source) {
        val request = ConnectionRequest.deserialize(stream)

        val accepted = ConnectionRequestAccepted(
            clientAddress = Address.from(address),
            systemIndex = 0u,
            systemAddresses = emptyList(),
            requestTimestamp = request.clientTimestamp,
            timestamp = Clock.System.now().toEpochMilliseconds().toULong()
        )

        send(
            Buffer().also { ConnectionRequestAccepted.serialize(accepted, it) }.readByteString(),
            RakReliability.ReliableOrdered,
            RakPriority.Normal,
        )
    }

    private fun handleConnectedPing(stream: Source) {
        val ping = ConnectedPing.deserialize(stream)

        val pong = ConnectedPong(
            pingTimestamp = ping.timestamp,
            timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
        )

        send(
            Buffer().also { ConnectedPong.serialize(pong, it) }.readByteString(),
            RakReliability.ReliableOrdered,
            RakPriority.Normal,
        )
    }

    companion object {
        val log = KotlinLogging.logger {}
    }
}