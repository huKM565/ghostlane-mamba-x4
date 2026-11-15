package org.olcbox.app.vpn.desktop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopOlcRtcBondTest {
    @Test
    fun bondRequiresFourRoomsAndIsAliveOnlyWhenAllAreAlive() {
        val rooms = rooms(4)
        assertFailsWith<IllegalArgumentException> { DesktopOlcRtcBond(rooms(3)) }
        val bond = DesktopOlcRtcBond(rooms)
        assertTrue(bond.isRunning())
        (rooms[3].process as FakeProcess).alive = false
        assertFalse(bond.isRunning())
        assertEquals(listOf(21081, 21082, 21083, 21084), bond.ports())
    }

    @Test
    fun stopAttemptsEveryProcessAndDeletesEveryConfig() {
        val rooms = rooms(4)
        (rooms[1].process as FakeProcess).failOnDestroy = true
        val bond = DesktopOlcRtcBond(rooms)

        val error = assertFailsWith<IllegalStateException> { bond.stop() }
        assertEquals("stop-2", error.message)
        assertTrue(rooms.all { !(it.process as FakeProcess).alive })
        bond.deleteConfigs()
        assertTrue(rooms.all { !Files.exists(it.configPath) })
    }

    private fun rooms(count: Int): List<DesktopOlcRtcBond.Room> = (1..count).map { index ->
        val path = Files.createTempFile("ilyukhaline-room-$index", ".yaml")
        Files.writeString(path, "test")
        DesktopOlcRtcBond.Room(FakeProcess(index), path, 21080 + index)
    }

    private class FakeProcess(private val id: Int) : Process() {
        var alive = true
        var failOnDestroy = false

        override fun destroy() {
            alive = false
            if (failOnDestroy) throw IllegalStateException("stop-$id")
        }

        override fun destroyForcibly(): Process = apply { alive = false }
        override fun isAlive(): Boolean = alive
        override fun waitFor(): Int {
            alive = false
            return 0
        }

        override fun exitValue(): Int = 0
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream() = ByteArrayOutputStream()
    }
}
