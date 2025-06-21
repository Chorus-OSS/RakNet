import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.sockets.*
import kotlinx.coroutines.runBlocking
import kotlinx.io.Source
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.io.readByteArray
import org.chorus_oss.raknet.connection.RakConnection
import org.chorus_oss.raknet.rakServer
import org.chorus_oss.raknet.server.RakServer
import org.chorus_oss.raknet.types.RakConnectionFactory
import org.chorus_oss.raknet.types.RakMOTD
import kotlin.test.Test

var rakServer: RakServer? = null

class Main {
    @Test
    fun main() {
        rakServer = rakServer("0.0.0.0", 19132, ConnectionFactory()) {
            advertisement = RakMOTD(
                edition = "MCPE",
                name = "Chorus/RakNet",
                protocol = 0,
                version = "1.0.0",
                playerCount = 0,
                playerMax = maxConnections,
                guid = guid,
                subName = "chorus-oss.org",
                gamemode = "Survival",
                nintendoLimited = false,
                port = 19132
            ).toString().encodeToByteString()
        }
        rakServer?.start(wait = true)
    }
}

class ConnectionFactory : RakConnectionFactory() {
    override fun create(server: RakServer, address: SocketAddress, guid: ULong, mtu: UShort): RakConnection {
        return Connection(server, address, guid, mtu)
    }
}

class Connection(server: RakServer, address: SocketAddress, guid: ULong, mtu: UShort) : RakConnection(server, address, guid, mtu) {
    override fun onConnect() {
        log.info { "Connected on $address, with guid: $guid" }
    }

    override fun onDisconnect() {
        log.info { "Disconnected on $address" }
    }

    @OptIn(ExperimentalStdlibApi::class)
    override fun onPacket(stream: Source) {
        log.info { "Packet from $address: ${stream.readByteArray().toHexString(HexFormat.UpperCase)}" }
        rakServer?.stop()
    }

    override fun onError(error: Error) {
        log.error { "Error from $address: $error" }
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}

