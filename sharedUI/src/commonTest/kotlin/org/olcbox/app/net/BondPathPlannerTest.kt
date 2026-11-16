package org.olcbox.app.net

import org.olcbox.app.data.model.BondRoomConfig
import org.olcbox.app.data.model.LocationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BondPathPlannerTest {
    @Test
    fun mixedProfileKeepsEveryPathAndAllocatesOneDistinctPortPerRoom() {
        val paths = mixedPaths()
        val config = config(paths)
        val available = ArrayDeque((21081..21092).toList())

        val plan = planBondPaths(config) { available.removeFirst() }

        assertEquals(paths, plan.paths)
        assertEquals((21081..21092).toList(), plan.ports)
        assertEquals(12, plan.ports.toSet().size)
        assertEquals(
            List(4) { "jitsi" } + List(4) { "vkcalls" } + List(4) { "telemost" },
            plan.paths.map { it.provider }
        )
        assertEquals(
            List(4) { "datachannel" } + List(8) { "vp8channel" },
            plan.paths.map { it.transport }
        )
    }

    @Test
    fun duplicateAllocatedPortFailsInsteadOfStartingTwoRoomsOnOneEndpoint() {
        val config = config(mixedPaths().take(4))
        var calls = 0

        assertFailsWith<IllegalArgumentException> {
            planBondPaths(config) {
                calls++
                if (calls == 4) 21083 else 21080 + calls
            }
        }
    }

    @Test
    fun ordinarySingleRoomProfileCannotBePlannedAsABond() {
        assertFailsWith<IllegalArgumentException> {
            planBondPaths(LocationConfig(id = "single", key = "key")) { 21081 }
        }
    }

    private fun config(paths: List<BondRoomConfig>) = LocationConfig(
        name = "Multi",
        id = paths.first().room,
        key = paths.first().key,
        bypassProvider = paths.first().provider,
        transport = paths.first().transport,
        bondVlessLink = "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:10000" +
            "?security=none&encryption=none#VLESS",
        bondedRoomConfigs = paths,
    )

    private fun mixedPaths(): List<BondRoomConfig> {
        val mamba = (1..4).map { BondRoomConfig("m$it", "a".repeat(64), "jitsi", "datachannel") }
        val vk = (1..4).map { BondRoomConfig("v$it", "b".repeat(64), "vkcalls", "vp8channel") }
        val telemost = (1..4).map { BondRoomConfig("t$it", "c".repeat(64), "telemost", "vp8channel") }
        return mamba + vk + telemost
    }
}
