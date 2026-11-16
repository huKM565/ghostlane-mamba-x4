package org.olcbox.app.vpn.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

enum class DesktopBondWatchDecision {
    Continue,
    Refresh,
    Restart,
}

fun desktopBondNeedsRefresh(active: List<Int>, balanced: List<Int>): Boolean =
    active != balanced || active.size < 4

fun desktopBondWatchDecision(
    coresAlive: Boolean,
    activeRooms: List<Int>?,
    balancedRooms: List<Int>,
): DesktopBondWatchDecision {
    if (activeRooms == null) return if (coresAlive) DesktopBondWatchDecision.Continue else DesktopBondWatchDecision.Restart
    if (desktopBondNeedsRefresh(activeRooms, balancedRooms)) return DesktopBondWatchDecision.Refresh
    return if (coresAlive) DesktopBondWatchDecision.Continue else DesktopBondWatchDecision.Restart
}

fun desktopBondWatchDecisionAfterRefresh(
    refresh: Result<Boolean>,
    coresAlive: Boolean,
): DesktopBondWatchDecision = when {
    refresh.isSuccess && refresh.getOrThrow() && coresAlive -> DesktopBondWatchDecision.Continue
    else -> DesktopBondWatchDecision.Restart
}

class DesktopOlcRtcBond(
    val rooms: List<Room>,
) {
    init {
        require(rooms.size == 4) { "a desktop olcRTC bond requires exactly four rooms" }
        require(rooms.map { it.port }.distinct().size == 4) { "bonded rooms need distinct ports" }
    }

    data class Room(
        var process: Process,
        val configPath: Path,
        val port: Int,
        val restart: (() -> Process)? = null,
    )

    fun activeIndexes(): List<Int> = rooms.mapIndexedNotNull { index, room ->
        index.takeIf { room.process.isAlive }
    }

    fun recover(): List<Int> {
        rooms.forEach { room ->
            if (room.process.isAlive) return@forEach
            runCatching { room.restart?.invoke() }
                .onSuccess { process -> if (process != null) room.process = process }
        }
        return activeIndexes()
    }

    fun isRunning(): Boolean = activeIndexes().size >= 3

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

    fun ports(): List<Int> = rooms.filter { it.process.isAlive }.map { it.port }
}
