package com.palmerintech.firetube.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Dragging an open Now Playing (the playerSheetDrag modifier it uses) down and back. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NowPlayingDragTest {

    @get:Rule val compose = createComposeRule()

    private lateinit var sheet: PlayerSheetState

    private fun show() = compose.setContent {
        sheet = rememberPlayerSheetState(initiallyExpanded = true).apply { travel = 1000f }
        Box(Modifier.fillMaxSize().testTag(TAG).playerSheetDrag(sheet, flingPx = 2000f))
    }

    private fun touch(block: TouchInjectionScope.() -> Unit) {
        compose.onNodeWithTag(TAG).performTouchInput(block)
        compose.waitForIdle()
    }

    /** Moves the finger down by [fraction] of the travel in [steps] steps of 16ms. */
    private fun TouchInjectionScope.moveDown(fraction: Float, steps: Int = 20) = repeat(steps) {
        advanceEventTime(16)
        moveBy(Offset(0f, 1000f * fraction / steps))
    }

    private fun assertSettled() {
        val p = sheet.progress
        assertTrue("left part-way at $p", p == 0f || p == 1f)
    }

    @Test
    fun releasingWhileStillMovingSettlesAllTheWay() {
        show()
        // The reported bug: let go mid-swipe, finger still moving. It must not stay part-way.
        touch { down(Offset(100f, 100f)); moveDown(0.4f); up() }
        assertSettled()
        assertFalse(sheet.expanded)
    }

    @Test
    fun aSlowDragPastAQuarterCloses() {
        show()
        touch {
            down(Offset(100f, 100f)); moveDown(0.4f, steps = 40)
            repeat(5) { advanceEventTime(40); moveBy(Offset.Zero) }
            up()
        }
        assertSettled()
        assertFalse(sheet.expanded)
    }

    @Test
    fun aShortSlowDragSpringsBackOpen() {
        show()
        touch {
            down(Offset(100f, 100f)); moveDown(0.1f, steps = 20)
            repeat(5) { advanceEventTime(40); moveBy(Offset.Zero) }
            up()
        }
        assertSettled()
        assertTrue(sheet.expanded)
        assertEquals(1f, sheet.progress, 0.001f)
    }

    @Test
    fun grabbingItMidSettleStopsTheAnimationAndFollowsTheFinger() {
        show()
        compose.mainClock.autoAdvance = false
        touch { down(Offset(100f, 100f)); moveDown(0.4f); up() } // starts closing
        compose.mainClock.advanceTimeBy(48)
        val caught = sheet.progress
        assertTrue("should be mid-animation, was $caught", caught > 0f && caught < 1f)
        touch { down(Offset(100f, 300f)); advanceEventTime(16); moveBy(Offset(0f, -200f)) } // grab and push up
        compose.mainClock.advanceTimeBy(500)
        // Held: it stays under the finger (higher than where it was caught), not animating away.
        assertTrue(sheet.progress > caught)
        val held = sheet.progress
        compose.mainClock.advanceTimeBy(500)
        assertEquals(held, sheet.progress, 0.001f)
        touch { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertSettled()
    }

    private companion object {
        const val TAG = "now-playing"
    }
}
