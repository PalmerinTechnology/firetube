package com.palmerintech.firetube.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * How far Now Playing is pulled up out of the mini player. Both the mini player and Now Playing
 * drive the same [progress], so the screen follows the finger in either direction and settles
 * open or closed on release.
 *
 * Drags apply synchronously and the settle animation runs in [scope], owned here. Drag deltas
 * used to be launched as separate coroutines; on a device the last few of a quick release could
 * run after the settle animation had started, cancel it, and leave the sheet stuck part-way.
 */
@Stable
class PlayerSheetState(initiallyExpanded: Boolean, private val scope: CoroutineScope) {
    /** 0 = collapsed into the mini player, 1 = Now Playing fills the screen. */
    var progress by mutableFloatStateOf(if (initiallyExpanded) 1f else 0f)
        private set

    /** Where the sheet is (or is heading): open or closed. Flips as soon as a settle starts. */
    var expanded by mutableStateOf(initiallyExpanded)
        private set

    /** Pixels Now Playing's top edge travels between collapsed (the mini player's top) and expanded. */
    var travel by mutableFloatStateOf(1f)

    /**
     * Now Playing needs to be on screen: open, opening, or part-way through a drag. Derived, so
     * readers recompose only when it flips, not on every frame of a drag.
     */
    val isVisible: Boolean by derivedStateOf { expanded || progress > 0f }

    private var animation: Job? = null

    /** Follows a vertical drag; [deltaPx] is negative when the finger moves up. Grabbing it mid-settle stops the settle. */
    fun dragBy(deltaPx: Float) {
        animation?.cancel()
        progress = (progress - deltaPx / travel.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    /** Finishes a drag that let go with [velocityPx] (px/s, negative = upward). */
    fun settle(velocityPx: Float, flingPx: Float) =
        animateTo(settleTarget(progress, velocityPx, flingPx, expanded), velocityPx)

    fun expand() = animateTo(true)

    fun collapse() = animateTo(false)

    private fun animateTo(open: Boolean, velocityPx: Float = 0f) {
        expanded = open
        animation?.cancel()
        animation = scope.launch {
            // Carry the finger's speed into the spring (progress grows as the finger moves up). A
            // fast flick makes the spring overshoot; clamp it, since nothing past fully open or
            // closed exists (and a negative corner radius would crash Now Playing).
            val velocity = -velocityPx / travel.coerceAtLeast(1f)
            animate(progress, if (open) 1f else 0f, velocity, spring(stiffness = Spring.StiffnessMediumLow)) { value, _ ->
                progress = value.coerceIn(0f, 1f)
            }
        }
    }
}

@Composable
fun rememberPlayerSheetState(initiallyExpanded: Boolean = false): PlayerSheetState {
    val scope = rememberCoroutineScope()
    return remember { PlayerSheetState(initiallyExpanded, scope) }
}

/** Lets a vertical drag on this element move the sheet, settling on release. */
@Composable
fun Modifier.playerSheetDrag(sheet: PlayerSheetState, flingPx: Float): Modifier {
    val drag = rememberDraggableState { delta -> sheet.dragBy(delta) }
    return draggable(drag, Orientation.Vertical, onDragStopped = { velocity -> sheet.settle(velocity, flingPx) })
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
