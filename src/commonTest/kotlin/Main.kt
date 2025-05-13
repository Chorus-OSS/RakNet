import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.sockets.*
import kotlinx.coroutines.runBlocking
import kotlinx.io.Source
import kotlinx.io.readByteArray
import org.chorus_oss.raknet.connection.RakConnection
import org.chorus_oss.raknet.server.RakServer
import org.chorus_oss.raknet.types.RakConnectionFactory
import kotlin.test.Test

class Main {
    @Test
    fun main() {
        runBlocking {
            val rakServer = RakServer.create("0.0.0.0", 19132, ConnectionFactory())
            rakServer.start()

            while (true) {}
        }
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
    }

    override fun onError(error: Error) {
        log.error { "Error from $address: $error" }
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}

