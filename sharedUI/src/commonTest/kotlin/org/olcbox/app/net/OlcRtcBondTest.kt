package org.olcbox.app.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OlcRtcBondTest {
    @Test
    fun bondRequiresExactlyFourRuntimes() {
        assertFailsWith<IllegalArgumentException> { OlcRtcBond(fakes(3)) }
        assertFailsWith<IllegalArgumentException> { OlcRtcBond(fakes(5)) }
    }

    @Test
    fun startLaunchesAllButWaitsOnlyForTheFirstReadyRuntime() {
        val events = mutableListOf<String>()
        val runtimes = fakes(4, events)
        val bond = OlcRtcBond(runtimes)

        val active = bond.start(12000)

        assertEquals(listOf(0), active)
        assertEquals(listOf("start-1", "start-2", "start-3", "start-4", "ready-1-12000"), events)
        assertTrue(bond.isRunning())
    }

    @Test
    fun failedRoomBeforeFirstSuccessIsStoppedAndExcluded() {
        val runtimes = fakes(4)
        runtimes[0].failReady = true
        val bond = OlcRtcBond(runtimes)

        val active = bond.start(12000)

        assertEquals(listOf(1), active)
        assertEquals(listOf(false, true, true, true), runtimes.map { it.running })
        assertEquals(1, runtimes[0].stopCalls)
        assertTrue(bond.isRunning())
        assertEquals(listOf(1, 2, 3), bond.recover(12000))
    }

    @Test
    fun twoFailedRoomsLeaveTwoRunning() {
        val runtimes = fakes(4)
        runtimes[0].failReady = true
        runtimes[1].failReady = true
        val bond = OlcRtcBond(runtimes)

        val active = bond.start(12000)

        assertEquals(listOf(2), active)
        assertTrue(bond.isRunning())
        assertEquals(listOf(2, 3), bond.recover(12000))
    }

    @Test
    fun firstReadyRoomStartsTheTunnelWithoutWaitingForTheRest() {
        val events = mutableListOf<String>()
        val runtimes = fakes(4, events)
        runtimes[1].failReady = true
        runtimes[2].failReady = true
        runtimes[3].failReady = true
        val bond = OlcRtcBond(runtimes)

        val active = bond.start(12000)

        assertEquals(listOf(0), active)
        assertEquals(
            listOf("start-1", "start-2", "start-3", "start-4", "ready-1-12000"),
            events,
        )
        assertTrue(bond.isRunning())
        assertFalse(bond.needsFullRecovery())
    }

    @Test
    fun noReadyRoomsStopTheWholePool() {
        val runtimes = fakes(4)
        runtimes.forEach { it.failReady = true }
        val bond = OlcRtcBond(runtimes)

        val error = assertFailsWith<IllegalStateException> { bond.start(12000) }

        assertEquals("ready-4", error.message)
        assertTrue(runtimes.none { it.running })
        assertFalse(bond.isRunning())
    }

    @Test
    fun recoverReturnsAnExcludedRoomToThePool() {
        val runtimes = fakes(4)
        runtimes[2].failReady = true
        val bond = OlcRtcBond(runtimes)
        bond.start(12000)
        runtimes[2].failReady = false

        val active = bond.recover(12000)

        assertEquals(listOf(0, 1, 2, 3), active)
        assertEquals(listOf(true, true, true, true), runtimes.map { it.running })
    }

    @Test
    fun oneStoppedRuntimeIsExcludedWithoutKillingThePool() {
        val runtimes = fakes(4)
        val bond = OlcRtcBond(runtimes)
        bond.start(12000)
        bond.recover(12000)

        runtimes[3].running = false

        assertEquals(listOf(0, 1, 2), bond.activeIndexes())
        assertTrue(bond.isRunning())
        assertFalse(bond.needsFullRecovery())
    }

    @Test
    fun stopAttemptsEveryRuntimeWhenOneFails() {
        val runtimes = fakes(4)
        runtimes.forEach { it.running = true }
        runtimes[1].failStop = true
        val bond = OlcRtcBond(runtimes)

        val error = assertFailsWith<IllegalStateException> { bond.stop(5000) }

        assertEquals("stop-2", error.message)
        assertEquals(listOf(5000, 5000, 5000, 5000), runtimes.map { it.stopTimeouts })
        assertTrue(runtimes.none { it.running })
    }

    @Test
    fun dnsUpdateAttemptsEveryRuntimeWhenOneFails() {
        val runtimes = fakes(4)
        runtimes[1].failDns = true
        val bond = OlcRtcBond(runtimes)

        val error = assertFailsWith<IllegalStateException> { bond.setDNS("1.1.1.1:53") }

        assertEquals("dns-2", error.message)
        assertEquals(List(4) { "1.1.1.1:53" }, runtimes.map { it.dns })
    }

    @Test
    fun occupancyReplayRequestsOnlyActiveRooms() {
        val runtimes = fakes(4)
        val bond = OlcRtcBond(runtimes)
        bond.start(12000)
        bond.recover(12000)
        runtimes[3].running = false

        bond.requestOccupancy()

        assertEquals(listOf(1, 1, 1, 0), runtimes.map { it.occupancyRequests })
    }

    @Test
    fun onlyTheLastStoppedRuntimeMakesTheWholeBondNotRunning() {
        val runtimes = fakes(4)
        runtimes.forEach { it.running = true }
        val bond = OlcRtcBond(runtimes)
        bond.start(12000)
        bond.recover(12000)

        runtimes[2].running = false
        runtimes[3].running = false
        assertTrue(bond.isRunning())

        runtimes[0].running = false
        runtimes[1].running = false
        assertFalse(bond.isRunning())
    }

    private fun fakes(count: Int, events: MutableList<String> = mutableListOf()) =
        List(count) { FakeRuntime(it + 1, events) }

    private class FakeRuntime(
        private val id: Int,
        private val events: MutableList<String>,
    ) : OlcRtcBondRuntime {
        var running = false
        var failReady = false
        var failStop = false
        var failDns = false
        var stopCalls = 0
        var stopTimeouts = 0
        var occupancyRequests = 0
        var dns = ""

        override fun start() {
            events += "start-$id"
            running = true
        }

        override fun waitReady(timeoutMillis: Int) {
            events += "ready-$id-$timeoutMillis"
            if (failReady) throw IllegalStateException("ready-$id")
        }

        override fun stop(timeoutMillis: Int) {
            stopCalls++
            stopTimeouts = timeoutMillis
            running = false
            if (failStop) throw IllegalStateException("stop-$id")
        }

        override fun isRunning(): Boolean = running

        override fun requestOccupancy() {
            occupancyRequests++
        }

        override fun setDNS(servers: String) {
            dns = servers
            if (failDns) throw IllegalStateException("dns-$id")
        }
    }
}
