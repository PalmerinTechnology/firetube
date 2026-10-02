package com.palmerintech.firetube.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.player.PlayerUiState
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MiniPlayerSwipeTest {

    @get:Rule val compose = createComposeRule()

    private val track = Track("abc123", "A song", "An artist", 200, null)
    private var next = 0
    private var previous = 0
    private val sheet = PlayerSheetState()

    /** The mini player at the bottom of a screen, driving [sheet] the way FireTubeRoot does. */
    private fun show(hasNext: Boolean = true, hasPrevious: Boolean = true) = compose.setContent {
        val scope = rememberCoroutineScope()
        val fling = with(LocalDensity.current) { 800.dp.toPx() } // as in FireTubeRoot
        MaterialTheme {
            Box(Modifier.fillMaxSize()) {
                MiniPlayer(
                    PlayerUiState(current = track, hasNext = hasNext, hasPrevious = hasPrevious),
                    onTogglePlay = {}, onNext = { next++ }, onPrevious = { previous++ },
                    onOpen = { scope.launch { sheet.expand() } },
                    modifier = Modifier.testTag(TAG).align(Alignment.BottomCenter)
                        .onGloballyPositioned { sheet.travel = it.positionInRoot().y },
                    onDrag = { scope.launch { sheet.dragBy(it) } },
                    onDragEnd = { scope.launch { sheet.settle(it, fling) } },
                )
            }
        }
    }

    private fun swipe(block: androidx.compose.ui.test.TouchInjectionScope.() -> Unit) {
        compose.onNodeWithTag(TAG).performTouchInput(block)
        compose.waitForIdle()
    }

    /** A slow vertical drag (no fling) of [fraction] of the sheet's travel; negative = up. */
    private fun slowDrag(fraction: Float, hold: Boolean = false) = swipe {
        down(center)
        val steps = 30
        repeat(steps) {
            advanceEventTime(40)
            moveBy(Offset(0f, sheet.travel * fraction / steps))
        }
        // Rest before lifting so the release carries no velocity.
        repeat(5) { advanceEventTime(40); moveBy(Offset.Zero) }
        if (!hold) up()
    }

    @Test
    fun swipeLeftPlaysTheNextTrack() {
        show()
        swipe { swipeLeft() }
        assertEquals(listOf(1, 0), listOf(next, previous))
        assertFalse(sheet.expanded)
    }

    @Test
    fun swipeRightPlaysThePreviousTrack() {
        show()
        swipe { swipeRight() }
        assertEquals(listOf(0, 1), listOf(next, previous))
        assertFalse(sheet.expanded)
    }

    @Test
    fun swipingPastTheEndOfTheQueueSpringsBackWithoutSkipping() {
        show(hasNext = false, hasPrevious = false)
        swipe { swipeLeft() }
        swipe { swipeRight() }
        assertEquals(listOf(0, 0), listOf(next, previous))
    }

    @Test
    fun nowPlayingFollowsTheFingerWhileDragging() {
        show()
        slowDrag(-0.4f, hold = true)
        assertEquals(0.4f, sheet.progress.value, 0.05f)
        swipe { up() }
    }

    @Test
    fun draggingUpPastAQuarterOpensNowPlaying() {
        show()
        slowDrag(-0.4f)
        assertTrue(sheet.expanded)
        assertEquals(1f, sheet.progress.value, 0.001f)
    }

    @Test
    fun aShortDragSpringsBackClosed() {
        show()
        slowDrag(-0.1f)
        assertFalse(sheet.expanded)
        assertEquals(0f, sheet.progress.value, 0.001f)
    }

    @Test
    fun aQuickFlickUpOpensNowPlaying() {
        show()
        // Only 15% of the way, too little to open on distance alone; the speed carries it.
        swipe { swipe(center, center - Offset(0f, sheet.travel * 0.15f), durationMillis = 40) }
        assertTrue(sheet.expanded)
    }

    @Test
    fun tapStillOpensNowPlaying() {
        show()
        compose.onNodeWithTag(TAG).performClick()
        compose.waitForIdle()
        assertTrue(sheet.expanded)
        assertEquals(1f, sheet.progress.value, 0.001f)
    }

    private companion object {
        const val TAG = "mini-player"
    }
}
