import kotlinx.coroutines.runBlocking
import org.chorus_oss.raknet.server.Server
import kotlin.test.Test

class Main {
    @Test
    fun main() {
        val server = Server()

        runBlocking {
            server.bind("0.0.0.0", 19132)
            server.start()

            while (true) {}
        }
    }
}

