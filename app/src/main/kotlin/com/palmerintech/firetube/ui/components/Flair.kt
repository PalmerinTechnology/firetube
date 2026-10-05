package com.palmerintech.firetube.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.palmerintech.firetube.ui.theme.LocalFireBrushes
import androidx.compose.foundation.border
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.palmerintech.firetube.R

/** FireTube's flame (the launcher foreground), tinted. */
@Composable
fun FlameIcon(modifier: Modifier = Modifier, size: Dp = 28.dp, tint: Color = MaterialTheme.colorScheme.primary) {
    // The adaptive-icon foreground has safe-zone padding, so draw it a bit larger than the target.
    Icon(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, tint = tint, modifier = modifier.size(size * 1.6f))
}

/** Little animated equalizer bars for the song that's playing; frozen when paused. */
@Composable
fun PlayingBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    if (!playing) {
        Bars(modifier, color) { i -> PAUSED_HEIGHTS[i] }
        return
    }
    val transition = rememberInfiniteTransition(label = "bars")
    val bars = listOf(420, 560, 340).map { period ->
        transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(period), RepeatMode.Reverse), label = "bar$period")
    }
    // Values are read while drawing, so the animation redraws without recomposing.
    Bars(modifier, color) { i -> bars[i].value }
}

@Composable
private fun Bars(modifier: Modifier, color: Color, height: (Int) -> Float) {
    Canvas(modifier.size(18.dp)) {
        val barWidth = size.width / 5
        for (i in 0 until 3) {
            val barHeight = size.height * height(i)
            drawRoundRect(
                color = color,
                topLeft = Offset(x = barWidth * (i * 2), y = size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

private val PAUSED_HEIGHTS = floatArrayOf(0.45f, 0.8f, 0.3f)

/** Which song is playing, for highlighting it wherever it appears in a list. */
data class NowPlaying(val trackId: String? = null, val isPlaying: Boolean = false)

val LocalNowPlaying = compositionLocalOf { NowPlaying() }

/**
 * A red outline while focused — how D-pad users (Fire TV, Android TV, keyboards) see where they
 * are; Material's own focus state layer is too faint on a TV.
 */
fun Modifier.focusRing(shape: Shape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    // isFocused (not hasFocus): a row and its focused "more" button shouldn't both light up.
    this.onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(2.dp, color, shape) else Modifier)
}

/** The occasional "Enjoying FireTube?" card on Home. Dismissible; never shown to supporters. */
@Composable
fun SupportCard(onSupport: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(shape)
            .background(LocalFireBrushes.current.flame)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlameIcon(size = 36.dp, tint = Color.White)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.support_card_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                stringResource(R.string.support_card_body),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.92f),
            )
            // Wraps if the labels don't fit side by side.
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSupport,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.focusRing(RoundedCornerShape(50)),
                ) { Text(stringResource(R.string.support_card_support), fontWeight = FontWeight.SemiBold) }
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                    modifier = Modifier.focusRing(RoundedCornerShape(50)),
                ) { Text(stringResource(R.string.support_card_not_now)) }
            }
        }
    }
}
