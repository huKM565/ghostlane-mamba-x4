package org.olcbox.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class IlyukhaLinePaletteTest {
    @Test
    fun colorsMatchIlyukhaLineCyanAndOrangeTheme() {
        val scheme = OlcboxDarkColorScheme
        assertEquals(Color(0xFF22D3EE), scheme.primary)
        assertEquals(Color(0xFF103743), scheme.primaryContainer)
        assertEquals(Color(0xFFFF8A65), scheme.tertiary)

        val palette = PkPalette()
        assertEquals(Color(0xFF22D3EE), palette.accent)
        assertEquals(Color(0xFFFF8A65), palette.accent2)
    }
}
