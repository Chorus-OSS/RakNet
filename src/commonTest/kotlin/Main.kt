import kotlinx.coroutines.runBlocking
import org.chorus_oss.raknet.server.RakServer
import kotlin.test.Test

class Main {
    @Test
    fun main() {
        runBlocking {
            val rakServer = RakServer.bind("0.0.0.0", 19132)
            rakServer.start()

            while (true) {}
        }
    }
}

