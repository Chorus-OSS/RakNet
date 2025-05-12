package org.chorus_oss.raknet.types

data class MOTD(
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
        val list = mutableListOf(
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
        )

        if (port != null) {
            list.add(port.toString())
        }

        if (portV6 != null) {
            list.add(portV6.toString())
        }

        return list.joinToString(separator = ";", postfix = ";")
    }
}
