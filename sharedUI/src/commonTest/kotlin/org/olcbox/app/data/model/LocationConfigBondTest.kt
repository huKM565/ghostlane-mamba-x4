package org.olcbox.app.data.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocationConfigBondTest {
    @Test
    fun bondedRoomsKeepThePrimaryAndThreeExtrasInOrder() {
        val config = bondedConfig()

        assertEquals(listOf("room-1", "room-2", "room-3", "room-4"), config.bondedRooms())
        assertTrue(config.isComplete())
    }

    @Test
    fun duplicateRoomsCannotFormAFourRoomBond() {
        val config = bondedConfig(listOf("room-1", "room-2", "room-2", "room-4")).normalized()

        assertEquals(listOf("room-1", "room-2", "room-4"), config.bondedRooms())
        assertFalse(config.isComplete())
    }

    @Test
    fun bondRequiresExactlyFourRooms() {
        assertFalse(bondedConfig(listOf("room-1", "room-2", "room-3")).isComplete())
        assertFalse(bondedConfig(listOf("room-1", "room-2", "room-3", "room-4", "room-5")).isComplete())
    }

    @Test
    fun bondRequiresAVlessLink() {
        assertFalse(bondedConfig(link = "not a link").isComplete())
        assertFalse(bondedConfig(link = "hysteria2://password@127.0.0.1:443#HY2").isComplete())
    }

    @Test
    fun unmarkedLocationKeepsFailoverSemantics() {
        val config = bondedConfig(link = null).normalized()

        assertTrue(config.isComplete())
        assertEquals(emptyList(), config.bondedRooms())
        assertEquals(listOf("room-1", "room-2", "room-3", "room-4"), config.failoverRooms())
    }

    @Test
    fun bondSurvivesJsonSerialization() {
        val config = bondedConfig()

        val decoded = Json.decodeFromString(
            LocationConfig.serializer(),
            Json.encodeToString(LocationConfig.serializer(), config)
        )

        assertEquals(config, decoded)
    }

    @Test
    fun bondSurvivesLocationEntryNormalization() {
        val entry = LocationEntry.from("mamba-x4", bondedConfig()).normalized()

        assertEquals(VLESS_LINK, entry.bondVlessLink)
        assertEquals(VLESS_LINK, entry.location.bondVlessLink)
        assertEquals(listOf("room-1", "room-2", "room-3", "room-4"), entry.location.bondedRooms())
    }

    private fun bondedConfig(
        rooms: List<String> = listOf("room-1", "room-2", "room-3", "room-4"),
        link: String? = VLESS_LINK,
    ) = LocationConfig(
        name = "Mamba x4",
        id = rooms.first(),
        key = "a".repeat(64),
        bypassProvider = LocationConfig.PROVIDER_JITSI,
        transport = LocationConfig.TRANSPORT_DATACHANNEL,
        failoverRoomIds = rooms.drop(1),
        bondVlessLink = link,
    )

    private companion object {
        const val VLESS_LINK = "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:10000" +
            "?security=none&encryption=none#Bond"
    }
}
