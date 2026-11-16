package org.olcbox.app.ui.render

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import org.olcbox.app.ui.components.kit.PkRoomCard
import org.olcbox.app.ui.components.kit.SeatDisplay
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BondRoomIndicatorsTest {
    @Test fun cardMarksEachActiveAndInactiveBondRoom() {
        measuredOn(Phone("pixel", 393, 852), {
            PkRoomCard(
                title = "Mamba x4",
                tag = "DataChannel",
                emoji = null,
                selected = true,
                connectedHere = true,
                blocked = false,
                seats = SeatDisplay.None,
                freeText = null,
                freeIsFull = false,
                freeIsTight = false,
                history = emptyList(),
                pingMs = null,
                isMeasuring = false,
                isOffline = false,
                keyGone = false,
                wire = "a Mamba media session",
                bondRooms = listOf(true, true, false, true),
                onClick = {},
            )
        }) {
            for (room in listOf(1, 2, 4)) {
                assertEquals(1, onAllNodesWithContentDescription("Room $room: active").fetchSemanticsNodes().size)
            }
            assertEquals(1, onAllNodesWithContentDescription("Room 3: inactive").fetchSemanticsNodes().size)
        }
    }
}
