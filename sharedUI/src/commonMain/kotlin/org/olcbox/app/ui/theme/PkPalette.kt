package org.olcbox.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** ProofKit tokens with no Material 3 slot (site: --accent/--accent-2/--success/…). */
data class PkPalette(
    val accent: Color = Color(0xFF22D3EE),
    val accentSoft: Color = Color(0x1A22D3EE),
    val accent2: Color = Color(0xFFFF8A65),
    val accent2Soft: Color = Color(0x1AFF8A65),
    val success: Color = Color(0xFF4ADE80),
    val successSoft: Color = Color(0x1F4ADE80),
    val warning: Color = Color(0xFFF59E0B),
    val danger: Color = Color(0xFFF43F5E),
    val textDim: Color = Color(0xFF8B93A8),
    val textMuted: Color = Color(0xFF555A72),
    val gridLine: Color = Color(0x06FFFFFF),
    val tonBlue: Color = Color(0xFF0098EA),
    /**
     * A seat nobody holds. Darker than any card so a row of empty seats reads as
     * absence rather than as another surface.
     */
    val seatFree: Color = Color(0xFF22263A),
    /** A seat somebody else holds. Never lime — lime is reserved for yours. */
    val seatOther: Color = Color(0xFF4A5379),
    /** Indigo at text weight: MEASURE and anything else that reads as a link. */
    val link: Color = Color(0xFF22D3EE),
    /**
     * Darker than `outlineVariant`, for rules that separate bands of one screen
     * rather than outlining a component. A card border at this weight disappears.
     */
    val hairline: Color = Color(0xFF14172A)
)

val LocalPkPalette = staticCompositionLocalOf { PkPalette() }
