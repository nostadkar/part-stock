package com.partstock.scanner

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** App-wide theme switches, read by the theme so a change in Settings applies at once. */
object AppTheme {
    /** Android 12+: colors from the wallpaper. Loaded from [Prefs.dynamicColor] at start. */
    var dynamicColor by mutableStateOf(true)
}

/**
 * Part Stock's own colors: a navy primary (the launcher icon's background, #1F3A5F) with an amber accent (the chip
 * leads in the icon). Used before Android 12 and when Settings → Wallpaper colors is off.
 */
object BrandColors {
    val Navy = Color(0xFF1F3A5F)
    val Amber = Color(0xFFF2B33D)

    val light = lightColorScheme(
        primary = Color(0xFF2E5B8A),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD3E4FF),
        onPrimaryContainer = Color(0xFF001C38),
        inversePrimary = Color(0xFFA2C9FF),
        secondary = Color(0xFF545F70),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD8E3F8),
        onSecondaryContainer = Color(0xFF111C2B),
        tertiary = Color(0xFF7A5900),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFDEA6),
        onTertiaryContainer = Color(0xFF261900),
        background = Color(0xFFF8F9FF),
        onBackground = Color(0xFF191C20),
        surface = Color(0xFFF8F9FF),
        onSurface = Color(0xFF191C20),
        surfaceVariant = Color(0xFFDFE2EB),
        onSurfaceVariant = Color(0xFF43474E),
        surfaceTint = Color(0xFF2E5B8A),
        inverseSurface = Color(0xFF2E3135),
        inverseOnSurface = Color(0xFFEFF0F7),
        outline = Color(0xFF73777F),
        outlineVariant = Color(0xFFC3C6CF),
        surfaceBright = Color(0xFFF8F9FF),
        surfaceDim = Color(0xFFD8DAE0),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF2F3F9),
        surfaceContainer = Color(0xFFECEEF4),
        surfaceContainerHigh = Color(0xFFE6E8EE),
        surfaceContainerHighest = Color(0xFFE1E2E8),
    )

    val dark = darkColorScheme(
        primary = Color(0xFFA2C9FF),
        onPrimary = Color(0xFF00315B),
        primaryContainer = Color(0xFF0F4471),
        onPrimaryContainer = Color(0xFFD3E4FF),
        inversePrimary = Color(0xFF2E5B8A),
        secondary = Color(0xFFBCC7DB),
        onSecondary = Color(0xFF263141),
        secondaryContainer = Color(0xFF3C4758),
        onSecondaryContainer = Color(0xFFD8E3F8),
        tertiary = Color(0xFFF8BD41),
        onTertiary = Color(0xFF402D00),
        tertiaryContainer = Color(0xFF5C4200),
        onTertiaryContainer = Color(0xFFFFDEA6),
        background = Color(0xFF111418),
        onBackground = Color(0xFFE1E2E8),
        surface = Color(0xFF111418),
        onSurface = Color(0xFFE1E2E8),
        surfaceVariant = Color(0xFF43474E),
        onSurfaceVariant = Color(0xFFC3C6CF),
        surfaceTint = Color(0xFFA2C9FF),
        inverseSurface = Color(0xFFE1E2E8),
        inverseOnSurface = Color(0xFF2E3135),
        outline = Color(0xFF8D9199),
        outlineVariant = Color(0xFF43474E),
        surfaceBright = Color(0xFF37393E),
        surfaceDim = Color(0xFF111418),
        surfaceContainerLowest = Color(0xFF0C0E13),
        surfaceContainerLow = Color(0xFF191C20),
        surfaceContainer = Color(0xFF1D2024),
        surfaceContainerHigh = Color(0xFF282A2F),
        surfaceContainerHighest = Color(0xFF33353A),
    )
}
