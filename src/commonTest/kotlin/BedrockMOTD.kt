import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString

data class BedrockMOTD(
    var edition: String = "MCPE",
    var name: String,
    var protocol: Int,
    var version: String,
    var playerCount: Int,
    var playerMax: Int,
    var guid: ULong,
    var subName: String,
    var gamemode: String,
    var nintendoLimited: Boolean = false,
    var port: Int? = 19132,
    var portV6: Int? = null,
) {
    override fun toString(): String {
        return listOfNotNull(
            edition,
            name,
            protocol,
            version,
            playerCount,
            playerMax,
            guid,
            subName,
            gamemode,
            if (nintendoLimited) 0 else 1,
            port?.toString(),
            portV6?.toString(),
        ).joinToString(separator = ";", postfix = ";")
    }

    fun toByteString(): ByteString {
        return this.toString().encodeToByteString()
    }
}