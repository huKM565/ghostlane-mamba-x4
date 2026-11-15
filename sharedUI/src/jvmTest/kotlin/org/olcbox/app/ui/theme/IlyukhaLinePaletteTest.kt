package org.olcbox.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class IlyukhaLinePaletteTest {
    @Test
    fun forkUsesCyanAndOrangeInsteadOfGhostlaneColors() {
        assertEquals(Color(0xFF22D3EE), OlcboxDarkColorScheme.primary)
        assertEquals(Color(0xFF103743), OlcboxDarkColorScheme.primaryContainer)
        assertEquals(Color(0xFFFF8A65), OlcboxDarkColorScheme.tertiary)
        assertEquals(Color(0xFF22D3EE), PkPalette().accent)
        assertEquals(Color(0xFFFF8A65), PkPalette().accent2)
    }
}
