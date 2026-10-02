package com.palmerintech.firetube.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A color taken from the artwork for tinting the player, or null when there's none (no artwork,
 * or no usable color). On a track change the previous color stays until the new one is ready, so
 * the tint doesn't flash back to the theme color in between.
 */
@Composable
fun rememberArtworkColor(url: String?, dark: Boolean): Color? {
    val context = LocalContext.current
    var color by remember(dark) { mutableStateOf<Color?>(null) }
    LaunchedEffect(url, dark) {
        if (url == null) { color = null; return@LaunchedEffect }
        val request = ImageRequest.Builder(context).data(url).size(96).allowHardware(false).build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
        if (bitmap == null) { color = null; return@LaunchedEffect }
        color = withContext(Dispatchers.Default) {
            val palette = Palette.from(bitmap).maximumColorCount(16).generate()
            val swatch = palette.vibrantSwatch ?: palette.darkVibrantSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch
            swatch?.rgb?.let { Color(playerTint(it, dark)) }
        }
    }
    return color
}

/**
 * Keeps an artwork color usable behind white (dark theme) or black (light theme) text: same hue,
 * saturation capped so neon covers don't glare, lightness pinned to a band that leaves contrast.
 */
internal fun playerTint(argb: Int, dark: Boolean): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(argb, hsl)
    hsl[1] = hsl[1].coerceAtMost(0.75f)
    hsl[2] = if (dark) hsl[2].coerceIn(0.22f, 0.38f) else hsl[2].coerceIn(0.70f, 0.85f)
    return ColorUtils.HSLToColor(hsl)
}
