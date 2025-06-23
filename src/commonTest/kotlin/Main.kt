import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.io.readByteArray
import org.chorus_oss.raknet.rakClient
import org.chorus_oss.raknet.rakServer
import org.chorus_oss.raknet.server.RakServer
import kotlin.test.Test

@OptIn(ExperimentalStdlibApi::class)
class Main {
    var rakServer: RakServer? = null

    @Test
    fun main() {
        rakServer = rakServer("0.0.0.0", 19132) {
            message = BedrockMOTD(
                name = "chorus-oss.org",
                protocol = 0,
                version = "1.0.0",
                playerCount = 0,
                playerMax = -1,
                guid = guid,
                subName = "RakNet",
                gamemode = "Adventure"
            ).also {
                log.info { "Using BedrockMOTD: $it" }
            }.toByteString()

            onConnect { connection ->
                log.info { "Connected on ${connection.address}, with guid: ${connection.guid}" }

                connection.onPacket { stream ->
                    log.info { "Packet from ${connection.address}: ${stream.readByteArray().toHexString(HexFormat.UpperCase)}" }
                }

                connection.onError { error ->
                    log.error { "Error from ${connection.address}: $error" }
                }
            }

            onDisconnect { connection ->
                log.info { "Disconnected on ${connection.address}" }
                rakServer?.stop()
            }
        }
        rakServer?.start(wait = true)
    }

    @Test
    fun client() {
        val client = rakClient {
            infoLogging = true
        }

        CoroutineScope(Dispatchers.Default).launch {
            delay(3000)
            client.disconnect()
        }

        client.connect("127.0.0.1", 19132, wait = true)
    }

    companion object {
        val log = KotlinLogging.logger {}
    }
}

