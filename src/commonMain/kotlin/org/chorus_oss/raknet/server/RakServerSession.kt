package org.chorus_oss.raknet.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.datetime.Clock
import kotlinx.io.Buffer
import kotlinx.io.Source
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

class RakServerSession(
    scope: CoroutineScope,
    outbound: SendChannel<Datagram>,
    address: InetSocketAddress,
    guid: ULong,
    mtu: UShort,
    private val onDisconnect: (RakServerSession) -> Unit,
    private val onConnect: (RakServerSession) -> Unit
) : RakSession(
    scope,
    outbound,
    address,
    guid,
    mtu,
) {
    override fun handle(stream: Source) {
        val header = stream.peek().readUByte()

        if (state == RakSessionState.Connecting) {
            when (header) {
                RakPacketID.DISCONNECT -> {
                    state = RakSessionState.Disconnecting
                    disconnect(send = false, connected = false)
                    state = RakSessionState.Disconnected
                }

                RakPacketID.CONNECTION_REQUEST -> {
                    handleConnectionRequest(stream)
                }

                RakPacketID.NEW_INCOMING_CONNECTION -> {
                    state = RakSessionState.Connected
                    onConnect()
                }

                else -> {
                    val id = header.toString(16).padStart(2, '0').uppercase()

                    log.debug { "Received unknown online packet \"0x$id\" from $address" }

                    onError(Error("Received unknown online packet \"0x$id\" from $address"))
                }
            }

            return
        }

        when (header) {
            RakPacketID.DISCONNECT -> {
                state = RakSessionState.Disconnecting
                disconnect(send = false, connected = true)
                state = RakSessionState.Disconnected
            }

            RakPacketID.CONNECTED_PING -> {
                handleConnectedPing(stream)
            }

            0xFE.toUByte() -> {
                onPacket(stream)
            }

            else -> {
                val id = header.toString(16).padStart(2, '0').uppercase()

                log.debug { "Received unknown online packet \"0x$id\" from $address" }

                onError(Error("Received unknown online packet \"0x$id\" from $address"))
            }
        }
    }

    override fun onConnect() {
        onConnect.invoke(this)
    }

    override fun onDisconnect() {
        onDisconnect.invoke(this)
    }

    private fun handleConnectionRequest(stream: Source) {
        val request = ConnectionRequest.deserialize(stream)

        val accepted = ConnectionRequestAccepted(
            clientAddress = Address.from(address),
            systemIndex = 0u,
            systemAddresses = emptyList(),
            requestTimestamp = request.clientTimestamp,
            timestamp = Clock.System.now().toEpochMilliseconds().toULong()
        )

        val frame = Frame(
            reliability = RakReliability.ReliableOrdered,
            orderChannel = 0u,
            payload = Buffer().also {
                ConnectionRequestAccepted.serialize(accepted, it)
            },
        )

        sendFrame(frame, RakPriority.Normal)
    }

    private fun handleConnectedPing(stream: Source) {
        val ping = ConnectedPing.deserialize(stream)

        val pong = ConnectedPong(
            pingTimestamp = ping.timestamp,
            timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
        )

        val frame = Frame(
            reliability = RakReliability.ReliableOrdered,
            orderChannel = 0u,
            payload = Buffer().also {
                ConnectedPong.serialize(pong, it)
            },
        )

        sendFrame(frame, RakPriority.Normal)
    }

    companion object {
        val log = KotlinLogging.logger {}
    }
}