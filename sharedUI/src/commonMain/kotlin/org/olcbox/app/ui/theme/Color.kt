package org.olcbox.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

// ProofKit Cypherpunk palette (site tokens → M3 roles). Dark-only brand — there is
// no light scheme: every AppTheme actual pins this one.
internal val OlcboxDarkColorScheme = darkColorScheme(
    primary = Color(0xFF22D3EE),
    onPrimary = Color(0xFF07080D),
    primaryContainer = Color(0xFF103743),
    onPrimaryContainer = Color(0xFF22D3EE),
    inversePrimary = Color(0xFF22D3EE),
    secondary = Color(0xFF8B93A8),
    onSecondary = Color(0xFF07080D),
    secondaryContainer = Color(0xFF161822),
    onSecondaryContainer = Color(0xFFE8ECF2),
    tertiary = Color(0xFFFF8A65),
    onTertiary = Color(0xFF1F0B05),
    tertiaryContainer = Color(0xFF3D1E16),
    onTertiaryContainer = Color(0xFFFF8A65),
    background = Color(0xFF07080D),
    onBackground = Color(0xFFE8ECF2),
    surface = Color(0xFF07080D),
    onSurface = Color(0xFFE8ECF2),
    surfaceVariant = Color(0xFF161822),
    onSurfaceVariant = Color(0xFF8B93A8),
    surfaceContainerLowest = Color(0xFF05060A),
    surfaceContainerLow = Color(0xFF0A0C14),
    surfaceContainer = Color(0xFF0F1117),
    surfaceContainerHigh = Color(0xFF161822),
    surfaceContainerHighest = Color(0xFF1D2030),
    inverseSurface = Color(0xFFE8ECF2),
    inverseOnSurface = Color(0xFF07080D),
    outline = Color(0xFF2A2D42),
    outlineVariant = Color(0xFF1E2030),
    error = Color(0xFFF43F5E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF3D141C),
    onErrorContainer = Color(0xFFF43F5E),
    scrim = Color(0xFF000000)
)
