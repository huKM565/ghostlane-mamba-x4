package org.olcbox.app.ui.render

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import org.olcbox.app.ui.components.kit.PkRoomCard
import org.olcbox.app.ui.components.kit.SeatDisplay
import org.olcbox.app.vpn.BondRoomError
import org.olcbox.app.vpn.BondRoomStatus
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
                bondRoomStates = listOf(
                    BondRoomStatus("M1", active = true, occupants = 1),
                    BondRoomStatus("M2", active = true, occupants = 0),
                    BondRoomStatus("M3", active = false),
                    BondRoomStatus("M4", active = true, occupants = 4),
                ),
                onClick = {},
            )
        }) {
            assertEquals(1, onAllNodesWithContentDescription("Room M1: active, 1 participants").fetchSemanticsNodes().size)
            assertEquals(1, onAllNodesWithContentDescription("Room M2: active, 0 participants").fetchSemanticsNodes().size)
            assertEquals(1, onAllNodesWithContentDescription("Room M3: inactive, participants unknown").fetchSemanticsNodes().size)
        }
    }

    @Test fun cardRendersTwelveMixedRoomsAndHonestErrors() {
        val states = List(12) { index ->
            val label = when (index) {
                in 0..3 -> "M${index + 1}"
                in 4..7 -> "V${index - 3}"
                else -> "T${index - 7}"
            }
            BondRoomStatus(
                label = label,
                active = index == 0,
                occupants = if (index == 0) 1 else null,
                error = when (index) {
                    1 -> BondRoomError.CLIENT
                    2 -> BondRoomError.SERVER
                    else -> null
                },
            )
        }
        measuredOn(Phone("pixel", 393, 852), {
            PkRoomCard(
                title = "Multi 12x",
                tag = "Mixed",
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
                wire = "mixed media sessions",
                bondRoomStates = states,
                onClick = {},
            )
        }) {
            states.forEachIndexed { index, status ->
                val activity = if (index == 0) "active" else "inactive"
                val occupants = if (index == 0) "1 participants" else "participants unknown"
                val error = when (status.error) {
                    BondRoomError.CLIENT -> ", Подключение не удалось"
                    BondRoomError.SERVER -> ", Сервер не смог подключиться"
                    null -> ""
                }
                assertEquals(
                    1,
                    onAllNodesWithContentDescription("Room ${status.label}: $activity, $occupants$error")
                        .fetchSemanticsNodes().size,
                )
            }
            assertEquals(1, onAllNodesWithText("Подключение не удалось").fetchSemanticsNodes().size)
            assertEquals(1, onAllNodesWithText("Сервер не смог подключиться").fetchSemanticsNodes().size)
        }
    }
}
