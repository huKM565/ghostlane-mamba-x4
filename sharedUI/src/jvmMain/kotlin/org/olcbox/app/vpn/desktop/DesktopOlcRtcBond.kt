package org.olcbox.app.vpn.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class DesktopOlcRtcBond(
    val rooms: List<Room>,
) {
    init {
        require(rooms.size == 4) { "a desktop olcRTC bond requires exactly four rooms" }
        require(rooms.map { it.port }.distinct().size == 4) { "bonded rooms need distinct ports" }
    }

    data class Room(
        val process: Process,
        val configPath: Path,
        val port: Int,
    )

    fun isRunning(): Boolean = rooms.all { it.process.isAlive }

    fun stop() {
        var firstError: Throwable? = null
        rooms.forEach { room ->
            try {
                val process = room.process
                if (process.isAlive) {
                    process.destroy()
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                    }
                }
            } catch (error: Throwable) {
                if (firstError == null) firstError = error
            }
        }
        firstError?.let { throw it }
    }

    fun deleteConfigs() {
        rooms.forEach { room ->
            runCatching { Files.deleteIfExists(room.configPath) }
        }
    }

    fun ports(): List<Int> = rooms.map { it.port }
}
