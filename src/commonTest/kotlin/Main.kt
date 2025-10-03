import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.*
import org.chorus_oss.raknet.rakClient
import org.chorus_oss.raknet.rakServer
import org.chorus_oss.raknet.server.RakServer
import org.chorus_oss.raknet.types.RakPriority
import org.chorus_oss.raknet.types.RakReliability
import kotlin.test.Test

@OptIn(ExperimentalStdlibApi::class)
class Main {


    @Test
    fun server() {
        var rakServer: RakServer? = null
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
                    log.info {
                        "Packet from ${connection.address}: ${
                            stream.readByteArray().toHexString(HexFormat.UpperCase)
                        }"
                    }
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
        rakServer.start(wait = true)
    }

    @Test
    fun client() {
        val client = rakClient("127.0.0.1", 19132) {
            connectionAttemptMax = 3
        }

        client.start(wait = true)
    }

    @Test
    fun both() {
        val server = rakServer("127.0.0.1", 19132) {
            message = BedrockMOTD(
                name = "chorus-oss.org",
                protocol = 0,
                version = "1.0.0",
                playerCount = 0,
                playerMax = -1,
                guid = guid,
                subName = "RakNet",
                gamemode = "Adventure"
            ).toByteString()

            onConnect { connection ->
                log.info { "Connected to ${connection.address}, with guid: ${connection.guid}" }

                connection.onPacket { stream ->
                    log.info {
                        "Packet from ${connection.address}: ${
                            stream.peek().readByteArray().toHexString(HexFormat.UpperCase)
                        }"
                    }

                    val id = stream.readUByte()
                    when (id.toUInt()) {
                        0xFEu -> {
                            val reply = stream.readUByte() == 0x01u.toUByte()
                            val message = stream.readString()

                            log.info { "Message from ${connection.address}: $message ${if (reply) "(reply)" else ""}" }

                            if (!reply) {
                                val packet = Buffer().apply {
                                    writeUByte(0xFEu)
                                    writeUByte(0x01u)
                                    writeString("Hey!")
                                }

                                connection.send(packet, RakReliability.ReliableOrdered, RakPriority.Normal)
                            }
                        }
                        else -> Unit
                    }
                }

                connection.onError { error ->
                    log.error { "Error from ${connection.address}: $error" }
                }
            }

            onDisconnect { connection ->
                log.info { "Disconnected from ${connection.address}" }
            }
        }

        val client = rakClient("127.0.0.1", 19132) {
            onConnect { connection ->
                log.info { "Connected to ${connection.address}, with guid: ${connection.guid}" }

                connection.onPacket { stream ->
                    log.info {
                        "Packet from ${connection.address}: ${
                            stream.peek().readByteArray().toHexString(HexFormat.UpperCase)
                        }"
                    }

                    val id = stream.readUByte()
                    when (id.toUInt()) {
                        0xFEu -> {
                            val reply = stream.readUByte() == 0x01u.toUByte()
                            val message = stream.readString()

                            log.info { "Message from ${connection.address}: $message ${if (reply) "(reply)" else ""}" }
                        }
                        else -> Unit
                    }
                }

                val packet = Buffer().apply {
                    writeUByte(0xFEu)
                    writeUByte(0x00u)
                    writeString("Hello?")
                }

                connection.send(packet, RakReliability.ReliableOrdered, RakPriority.Normal)
            }

            onDisconnect { connection ->
                log.info { "Disconnected from ${connection.address}" }
            }
        }

        server.start()
        client.start(wait = true)
    }

    companion object {
        val log = KotlinLogging.logger {}
    }
}

