package org.olcbox.app.ui.render

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import org.olcbox.app.ui.components.kit.PkRoomCard
import org.olcbox.app.vpn.BondRoomError
import org.olcbox.app.vpn.BondRoomStatus
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.net.OlcrtcSlots
import org.olcbox.app.ui.features.home.components.BoardGroup
import org.olcbox.app.ui.features.home.components.BoardModel
import org.olcbox.app.ui.features.home.components.RoomBoard
import org.olcbox.app.ui.features.home.components.TransportFilterOption
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.PingsState
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
                keyGone = false,
                wire = "a Mamba media session",
                bondRoomStates = listOf(
                    BondRoomStatus("M1", active = true, occupants = 1),
                    BondRoomStatus("M2", active = true, occupants = 0),
                    BondRoomStatus("M3", active = false),
                    BondRoomStatus("M4", active = true, occupants = 4),
                    BondRoomStatus("V1", active = true),
                ),
                onClick = {},
            )
        }) {
            assertEquals(1, onAllNodesWithContentDescription("Room M1: active, 1 participants").fetchSemanticsNodes().size)
            assertEquals(1, onAllNodesWithContentDescription("Room M2: active, 0 participants").fetchSemanticsNodes().size)
            assertEquals(1, onAllNodesWithContentDescription("Room M3: inactive, participants unknown").fetchSemanticsNodes().size)
            assertEquals(1, onAllNodesWithText("V1·✓").fetchSemanticsNodes().size)
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

    @Test fun boardKeepsEightProfilesAndDropsObsoleteChrome() {
        val profileNames = listOf("Mamba 4x", "VK 4x", "Телемост 4x", "Multi 12x")
        val groups = listOf("Германия", "Финляндия").map { groupName ->
            val subscription = SubscriptionMetadata(
                name = groupName,
                used = "1 GB",
                available = "2 GB",
                webPageUrl = "https://provider.example/site",
                supportUrl = "https://provider.example/support",
            )
            BoardGroup(
                key = groupName,
                locations = profileNames.mapIndexed { index, profile ->
                    val id = "$groupName-$index"
                    LocationItem(
                        storageId = id,
                        fullName = "$groupName $profile",
                        config = LocationConfig(
                            name = "$groupName $profile",
                            id = "room-$index",
                            key = "key",
                            bypassProvider = "jitsi",
                            transport = "datachannel",
                        ),
                        subscriptionUrl = "https://provider.example/$groupName",
                        metadata = LocationMetadata(subscription = subscription),
                    )
                },
            )
        }
        val allIds = groups.flatMap { group -> group.locations.map { it.storageId } }
        val states = List(12) { index -> BondRoomStatus("R${index + 1}", active = index == 0) }
        measuredOn(Phone("pixel", 393, 1000), {
            RoomBoard(
                model = BoardModel(
                    filterOptions = listOf(
                        TransportFilterOption("jitsi", "Jitsi", 0),
                        TransportFilterOption("vk", "VK", 1),
                    ),
                    filterCounts = mapOf("jitsi" to 4, "vk" to 4),
                    totalCount = 8,
                    subscriptionGroups = groups,
                    customLocations = emptyList(),
                    hasRooms = true,
                    isEmpty = false,
                ),
                selectedLocationId = "Германия-3",
                isConnected = true,
                bondRoomStates = states,
                revokedKeys = emptySet(),
                collapsible = false,
                showSettings = false,
                showCustomLocation = false,
                refreshingSubscriptionUrl = null,
                foldedSubscriptionUrls = emptySet(),
                onLocationSelected = {},
                onFoldToggle = {},
                onPinToggle = {},
                onLocationSettingsClick = {},
                onRefreshSubscriptionClick = {},
                onDeleteLocationClick = {},
                onDeleteSubscriptionClick = {},
                onAddSubscriptionClick = {},
                onAddLocationClick = {},
            )
        }) {
            listOf("Германия", "Финляндия").forEach { group ->
                assertEquals(1, onAllNodesWithText(group).fetchSemanticsNodes().size)
            }
            groups.flatMap { it.locations }.forEach { location ->
                assertEquals(1, onAllNodesWithText(location.fullName).fetchSemanticsNodes().size)
            }
            assertEquals(0, onAllNodesWithText("MEASURE", substring = true).fetchSemanticsNodes().size)
            assertEquals(0, onAllNodesWithText("38 ms").fetchSemanticsNodes().size)
            assertEquals(0, onAllNodesWithText("4 free", substring = true).fetchSemanticsNodes().size)
            assertEquals(0, onAllNodesWithText("1 GB", substring = true).fetchSemanticsNodes().size)
            assertEquals(0, onAllNodesWithContentDescription("Open the provider's site").fetchSemanticsNodes().size)
            assertEquals(0, onAllNodesWithContentDescription("Contact support").fetchSemanticsNodes().size)
            assertEquals(1, onAllNodesWithContentDescription("Room R12: inactive, participants unknown").fetchSemanticsNodes().size)
        }
    }
}
