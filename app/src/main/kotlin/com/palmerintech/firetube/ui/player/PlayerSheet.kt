package com.palmerintech.firetube.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How far Now Playing is pulled up out of the mini player. Both the mini player and Now Playing
 * drive the same [progress], so the screen follows the finger in either direction and settles
 * open or closed on release.
 */
@Stable
class PlayerSheetState(initiallyExpanded: Boolean = false) {
    /** 0 = collapsed into the mini player, 1 = Now Playing fills the screen. */
    val progress = Animatable(if (initiallyExpanded) 1f else 0f)

    /** Where the sheet is (or is heading): open or closed. Flips as soon as a settle starts. */
    var expanded by mutableStateOf(initiallyExpanded)
        private set

    /** Pixels Now Playing's top edge travels between collapsed (the mini player's top) and expanded. */
    var travel by mutableFloatStateOf(1f)

    /** Now Playing needs to be on screen: open, opening, or part-way through a drag. */
    val isVisible: Boolean get() = expanded || progress.value > 0f

    /** Follows a vertical drag; [deltaPx] is negative when the finger moves up. */
    suspend fun dragBy(deltaPx: Float) {
        progress.snapTo((progress.value - deltaPx / travel.coerceAtLeast(1f)).coerceIn(0f, 1f))
    }

    /** Finishes a drag that let go with [velocityPx] (px/s, negative = upward). */
    suspend fun settle(velocityPx: Float, flingPx: Float) =
        animateTo(settleTarget(progress.value, velocityPx, flingPx, expanded))

    suspend fun expand() = animateTo(true)

    suspend fun collapse() = animateTo(false)

    private suspend fun animateTo(open: Boolean) {
        expanded = open
        progress.animateTo(if (open) 1f else 0f, spring(stiffness = Spring.StiffnessMediumLow))
    }
}

/**
 * Whether a released drag should end open. A flick decides on its own; otherwise the sheet has to
 * be dragged a quarter of the way from where it started (up from the mini player, down from Now
 * Playing) to change state, so a small wobble springs back.
 */
internal fun settleTarget(progress: Float, velocityPx: Float, flingPx: Float, wasExpanded: Boolean): Boolean = when {
    velocityPx < -flingPx -> true
    velocityPx > flingPx -> false
    wasExpanded -> progress > 1f - SETTLE_FRACTION
    else -> progress > SETTLE_FRACTION
}

private const val SETTLE_FRACTION = 0.25f
