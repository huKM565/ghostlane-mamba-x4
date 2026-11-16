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
    fun bondRequiresFourRoomsAndKeepsRunningWithThree() {
        val rooms = rooms(4)
        assertFailsWith<IllegalArgumentException> { DesktopOlcRtcBond(rooms(3)) }
        val bond = DesktopOlcRtcBond(rooms)
        assertTrue(bond.isRunning())
        (rooms[3].process as FakeProcess).alive = false
        assertTrue(bond.isRunning())
        assertEquals(listOf(21081, 21082, 21083), bond.ports())
        assertEquals(listOf(0, 1, 2), bond.activeIndexes())
        (rooms[2].process as FakeProcess).alive = false
        assertFalse(bond.isRunning())
    }

    @Test
    fun watcherMustRecoverAStableThreeRoomBondBackToFourRooms() {
        assertTrue(desktopBondNeedsRefresh(listOf(0, 1, 2), listOf(0, 1, 2, 3)))
        assertTrue(desktopBondNeedsRefresh(listOf(0, 1, 2), listOf(0, 1, 2)))
        assertFalse(desktopBondNeedsRefresh(listOf(0, 1, 2, 3), listOf(0, 1, 2, 3)))
    }

    @Test
    fun watcherContinuesOnlyAfterAHealthyRefreshAndRestartsAfterFailure() {
        assertEquals(
            DesktopBondWatchDecision.Continue,
            desktopBondWatchDecisionAfterRefresh(Result.success(true), coresAlive = true),
        )
        assertEquals(
            DesktopBondWatchDecision.Restart,
            desktopBondWatchDecisionAfterRefresh(Result.success(true), coresAlive = false),
        )
        assertEquals(
            DesktopBondWatchDecision.Restart,
            desktopBondWatchDecisionAfterRefresh(Result.success(false), coresAlive = true),
        )
        assertEquals(
            DesktopBondWatchDecision.Restart,
            desktopBondWatchDecisionAfterRefresh(Result.failure(IllegalStateException("rebalance failed")), coresAlive = true),
        )
    }

    @Test
    fun parsesOnlyStructuredRoomOccupancyEvents() {
        assertEquals(6, parseOlcRtcRoomParticipants("2026/11/16 16:02:32 jitsi: room participants=6"))
        assertEquals(2, parseOlcRtcRoomParticipants("jitsi: room participants=2"))
        assertEquals(null, parseOlcRtcRoomParticipants("jitsi: bridge open sctp"))
        assertEquals(0, parseOlcRtcRoomParticipants("jitsi: room participants=0"))
        assertEquals(null, parseOlcRtcRoomParticipants("jitsi: room participants=-1"))
    }

    @Test
    fun recoverRestartsAndReturnsTheMissingRoom() {
        val rooms = rooms(4)
        val replacement = FakeProcess(44)
        val stopped = rooms[3].copy(restart = { replacement })
        val bond = DesktopOlcRtcBond(rooms.take(3) + stopped)
        (stopped.process as FakeProcess).alive = false

        val active = bond.recover()

        assertEquals(listOf(0, 1, 2, 3), active)
        assertEquals(replacement, stopped.process)
        assertEquals(listOf(21081, 21082, 21083, 21084), bond.ports())
    }

    @Test
    fun singleEngineIsMissingOnlyWithoutBothProcessAndBond() {
        assertTrue(desktopOlcRtcProcessMissing(null, null))
        assertFalse(desktopOlcRtcProcessMissing(FakeProcess(1), null))
        assertFalse(desktopOlcRtcProcessMissing(null, DesktopOlcRtcBond(rooms(4))))
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
