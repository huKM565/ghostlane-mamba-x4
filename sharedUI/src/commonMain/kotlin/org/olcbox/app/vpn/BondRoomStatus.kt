package org.olcbox.app.vpn

import org.olcbox.app.data.model.LocationConfig

enum class BondRoomError {
    CLIENT,
    SERVER,
}

data class BondRoomStatus(
    val label: String,
    val active: Boolean,
    val occupants: Int? = null,
    val error: BondRoomError? = null,
)

fun displayedOccupants(raw: Int?, relayReady: Boolean): Int? {
    return raw?.let { (it - if (relayReady) 1 else 0).coerceAtLeast(0) }
}

fun bondRoomLabel(index: Int, provider: String): String {
    val suffix = index % 4 + 1
    return when (LocationConfig.normalizeProvider(provider)) {
        LocationConfig.PROVIDER_JITSI -> "M$suffix"
        LocationConfig.PROVIDER_VKCALLS -> "V$suffix"
        LocationConfig.PROVIDER_TELEMOST -> "T$suffix"
        else -> "R${index + 1}"
    }
}

fun BondRoomStatus.withOccupancy(raw: Int?, relayReady: Boolean): BondRoomStatus =
    copy(occupants = displayedOccupants(raw, relayReady))
