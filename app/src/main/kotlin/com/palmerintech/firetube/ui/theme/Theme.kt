package com.palmerintech.firetube.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.palmerintech.firetube.data.ThemeMode

/** The flame's red, from the launcher icon. */
val FireRed = Color(0xFFF44336)

private val Ember = Color(0xFFFF7043)
private val DeepRed = Color(0xFFB71C1C)

private val LightColors = lightColorScheme(
    primary = Color(0xFFD32F2F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFFE64A19),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCF),
    onSecondaryContainer = Color(0xFF3A0B00),
    tertiary = Color(0xFFB8860B),
    background = Color(0xFFFFFBFF),
    surface = Color(0xFFFFFBFF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFF4F2),
    surfaceContainer = Color(0xFFFCEDEA),
    surfaceContainerHigh = Color(0xFFF7E5E2),
    surfaceContainerHighest = Color(0xFFF1DFDC),
    outline = Color(0xFF8F706C),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF6E61),
    onPrimary = Color(0xFF3B0000),
    primaryContainer = Color(0xFF8C1D18),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFFF8A65),
    onSecondary = Color(0xFF3A0B00),
    secondaryContainer = Color(0xFF6B2A15),
    onSecondaryContainer = Color(0xFFFFDBCF),
    tertiary = Color(0xFFFFC857),
    background = Color(0xFF120B0A),
    surface = Color(0xFF120B0A),
    surfaceContainerLowest = Color(0xFF0C0605),
    surfaceContainerLow = Color(0xFF1B1210),
    surfaceContainer = Color(0xFF211715),
    surfaceContainerHigh = Color(0xFF2C201E),
    surfaceContainerHighest = Color(0xFF372A28),
    outline = Color(0xFFA08C89),
)

/** Brand gradients used for the Home header, the mini player and the Now Playing backdrop. */
@Immutable
data class FireBrushes(
    /** Ember → red → deep red, for accents (play button ring, section markers). */
    val flame: Brush,
    /** A red wash that fades out downward, for screen headers. */
    val headerWash: Brush,
)

val LocalFireBrushes = staticCompositionLocalOf {
    FireBrushes(Brush.linearGradient(listOf(Ember, FireRed, DeepRed)), Brush.verticalGradient(listOf(FireRed, Color.Transparent)))
}

@Composable
fun FireTubeTheme(themeMode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val brushes = FireBrushes(
        flame = Brush.linearGradient(listOf(Ember, FireRed, DeepRed)),
        headerWash = Brush.verticalGradient(
            listOf(colors.primary.copy(alpha = if (dark) 0.42f else 0.30f), colors.primary.copy(alpha = 0.08f), Color.Transparent),
        ),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalFireBrushes provides brushes) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
