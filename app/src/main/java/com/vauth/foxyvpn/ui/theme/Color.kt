package com.vauth.foxyvpn.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val FoxSeed = Color(0xFFFF7139)

val md_theme_light_primary = Color(0xFFA03A00)
val md_theme_light_onPrimary = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer = Color(0xFFFFDBCB)
val md_theme_light_onPrimaryContainer = Color(0xFF351000)
val md_theme_light_inversePrimary = Color(0xFFFFB59B)

val md_theme_light_secondary = Color(0xFF765749)
val md_theme_light_onSecondary = Color(0xFFFFFFFF)
val md_theme_light_secondaryContainer = Color(0xFFFFDBCB)
val md_theme_light_onSecondaryContainer = Color(0xFF2B160C)

val md_theme_light_tertiary = Color(0xFF64612E)
val md_theme_light_onTertiary = Color(0xFFFFFFFF)
val md_theme_light_tertiaryContainer = Color(0xFFEBE7A6)
val md_theme_light_onTertiaryContainer = Color(0xFF1E1D00)

val md_theme_light_error = Color(0xFFBA1A1A)
val md_theme_light_onError = Color(0xFFFFFFFF)
val md_theme_light_errorContainer = Color(0xFFFFDAD6)
val md_theme_light_onErrorContainer = Color(0xFF410002)

val md_theme_light_background = Color(0xFFFFF8F6)
val md_theme_light_onBackground = Color(0xFF221A16)
val md_theme_light_surface = Color(0xFFFFF8F6)
val md_theme_light_onSurface = Color(0xFF221A16)
val md_theme_light_surfaceVariant = Color(0xFFF5DED5)
val md_theme_light_onSurfaceVariant = Color(0xFF53433D)
val md_theme_light_surfaceTint = md_theme_light_primary

val md_theme_light_surfaceContainerLowest = Color(0xFFFFFFFF)
val md_theme_light_surfaceContainerLow = Color(0xFFFFF1EC)
val md_theme_light_surfaceContainer = Color(0xFFFCEAE4)
val md_theme_light_surfaceContainerHigh = Color(0xFFF7E4DE)
val md_theme_light_surfaceContainerHighest = Color(0xFFF1DED8)
val md_theme_light_surfaceBright = Color(0xFFFFF8F6)
val md_theme_light_surfaceDim = Color(0xFFE6D5CF)

val md_theme_light_outline = Color(0xFF85736C)
val md_theme_light_outlineVariant = Color(0xFFD8C2BA)
val md_theme_light_inverseSurface = Color(0xFF382E2A)
val md_theme_light_inverseOnSurface = Color(0xFFFFEDE7)
val md_theme_light_scrim = Color(0xFF000000)

val md_theme_dark_primary = Color(0xFFFFB59B)
val md_theme_dark_onPrimary = Color(0xFF561D00)
val md_theme_dark_primaryContainer = Color(0xFF7A2C00)
val md_theme_dark_onPrimaryContainer = Color(0xFFFFDBCB)
val md_theme_dark_inversePrimary = Color(0xFFA03A00)

val md_theme_dark_secondary = Color(0xFFE6BEAF)
val md_theme_dark_onSecondary = Color(0xFF442A20)
val md_theme_dark_secondaryContainer = Color(0xFF5C4035)
val md_theme_dark_onSecondaryContainer = Color(0xFFFFDBCB)

val md_theme_dark_tertiary = Color(0xFFCFCB8D)
val md_theme_dark_onTertiary = Color(0xFF343205)
val md_theme_dark_tertiaryContainer = Color(0xFF4B4919)
val md_theme_dark_onTertiaryContainer = Color(0xFFEBE7A6)

val md_theme_dark_error = Color(0xFFFFB4AB)
val md_theme_dark_onError = Color(0xFF690005)
val md_theme_dark_errorContainer = Color(0xFF93000A)
val md_theme_dark_onErrorContainer = Color(0xFFFFDAD6)

val md_theme_dark_background = Color(0xFF1A1210)
val md_theme_dark_onBackground = Color(0xFFF1DED8)
val md_theme_dark_surface = Color(0xFF1A1210)
val md_theme_dark_onSurface = Color(0xFFF1DED8)
val md_theme_dark_surfaceVariant = Color(0xFF53433D)
val md_theme_dark_onSurfaceVariant = Color(0xFFD8C2BA)
val md_theme_dark_surfaceTint = md_theme_dark_primary

val md_theme_dark_surfaceContainerLowest = Color(0xFF140C0A)
val md_theme_dark_surfaceContainerLow = Color(0xFF221A16)
val md_theme_dark_surfaceContainer = Color(0xFF271E1A)
val md_theme_dark_surfaceContainerHigh = Color(0xFF322824)
val md_theme_dark_surfaceContainerHighest = Color(0xFF3D332E)
val md_theme_dark_surfaceBright = Color(0xFF413733)
val md_theme_dark_surfaceDim = Color(0xFF1A1210)

val md_theme_dark_outline = Color(0xFFA08D85)
val md_theme_dark_outlineVariant = Color(0xFF53433D)
val md_theme_dark_inverseSurface = Color(0xFFF1DED8)
val md_theme_dark_inverseOnSurface = Color(0xFF382E2A)
val md_theme_dark_scrim = Color(0xFF000000)

data class FoxyStatusColors(
    val connected: Color,
    val connecting: Color,
    val disconnected: Color,
)

val LightStatusColors = FoxyStatusColors(
    connected = Color(0xFF1B6E2F),
    connecting = Color(0xFF8A5A00),
    disconnected = md_theme_light_onSurfaceVariant,
)

val DarkStatusColors = FoxyStatusColors(
    connected = Color(0xFF7BDF92),
    connecting = Color(0xFFFFC46B),
    disconnected = md_theme_dark_onSurfaceVariant,
)

val LocalFoxyStatusColors = staticCompositionLocalOf { LightStatusColors }

val ConnectedGreen = LightStatusColors.connected
val ConnectingAmber = LightStatusColors.connecting
