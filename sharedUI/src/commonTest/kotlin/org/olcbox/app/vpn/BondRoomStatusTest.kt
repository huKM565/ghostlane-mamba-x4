package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BondRoomStatusTest {
    @Test
    fun displayedOccupantsSubtractsTheRelayOnlyWhenItIsPresent() {
        assertNull(displayedOccupants(null, relayReady = true))
        assertEquals(0, displayedOccupants(0, relayReady = false))
        assertEquals(1, displayedOccupants(2, relayReady = true))
        assertEquals(0, displayedOccupants(1, relayReady = true))
        assertEquals(2, displayedOccupants(2, relayReady = false))
    }

    @Test
    fun labelsFollowProviderAndPathOrder() {
        val providers = List(4) { "jitsi" } + List(4) { "vkcalls" } + List(4) { "telemost" } + List(4) { "salutejazz" }

        assertEquals(
            listOf("M1", "M2", "M3", "M4", "V1", "V2", "V3", "V4", "T1", "T2", "T3", "T4", "J1", "J2", "J3", "J4"),
            providers.mapIndexed(::bondRoomLabel),
        )
    }

    @Test
    fun laterOccupancyDoesNotEraseErrorOrReactivateTheRoom() {
        val status = BondRoomStatus(
            label = "M2",
            active = false,
            error = BondRoomError.SERVER,
        ).withOccupancy(raw = 4, relayReady = false)

        assertEquals(4, status.occupants)
        assertEquals(false, status.active)
        assertEquals(BondRoomError.SERVER, status.error)
    }
}
