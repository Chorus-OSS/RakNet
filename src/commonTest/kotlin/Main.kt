import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.io.readByteArray
import org.chorus_oss.raknet.rakServer
import org.chorus_oss.raknet.server.RakServer
import org.chorus_oss.raknet.types.RakMOTD
import kotlin.test.Test

@OptIn(ExperimentalStdlibApi::class)
class Main {
    var rakServer: RakServer? = null

    @Test
    fun main() {
        rakServer = rakServer("0.0.0.0", 19132) {
            message = RakMOTD(
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
            }
        }
        rakServer?.start(wait = true)
    }

    companion object {
        val log = KotlinLogging.logger {}
    }
}

