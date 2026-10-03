package com.palmerintech.firetube.ui.player

import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSheetStateTest {

    private val fling = 1000f

    @Test
    fun aFlickDecidesOnItsOwn() {
        assertTrue(settleTarget(progress = 0.05f, velocityPx = -1500f, flingPx = fling, wasExpanded = false))
        assertFalse(settleTarget(progress = 0.95f, velocityPx = 1500f, flingPx = fling, wasExpanded = true))
        // Flicked back the way it came: undo, even past the halfway mark.
        assertFalse(settleTarget(progress = 0.6f, velocityPx = 1500f, flingPx = fling, wasExpanded = false))
    }

    @Test
    fun aSlowReleaseNeedsAQuarterOfTheWayToChangeState() {
        assertFalse(settleTarget(progress = 0.2f, velocityPx = 0f, flingPx = fling, wasExpanded = false))
        assertTrue(settleTarget(progress = 0.3f, velocityPx = 0f, flingPx = fling, wasExpanded = false))
        assertTrue(settleTarget(progress = 0.8f, velocityPx = 0f, flingPx = fling, wasExpanded = true))
        assertFalse(settleTarget(progress = 0.7f, velocityPx = 0f, flingPx = fling, wasExpanded = true))
    }

    @Test
    fun draggingFollowsTheFingerAndStopsAtTheEnds() {
        val sheet = PlayerSheetState(initiallyExpanded = false, TestScope()).apply { travel = 1000f }
        sheet.dragBy(-250f) // finger up a quarter of the way
        assertEquals(0.25f, sheet.progress, 0.001f)
        assertTrue(sheet.isVisible)
        sheet.dragBy(-5000f)
        assertEquals(1f, sheet.progress, 0.001f)
        sheet.dragBy(9000f)
        assertEquals(0f, sheet.progress, 0.001f)
        assertFalse(sheet.isVisible)
    }

    @Test
    fun usesTheScreenHeightUntilTheMiniPlayerIsMeasured() {
        val sheet = PlayerSheetState(initiallyExpanded = false, TestScope()).apply { fallbackTravel = 2000f }
        assertEquals(2000f, sheet.distance, 0.001f)
        sheet.dragBy(-500f)
        assertEquals(0.25f, sheet.progress, 0.001f)
        sheet.travel = 1500f // measured
        assertEquals(1500f, sheet.distance, 0.001f)
    }

    @Test
    fun startsOpenWhenRestoredOpen() {
        val sheet = PlayerSheetState(initiallyExpanded = true, TestScope())
        assertEquals(1f, sheet.progress, 0.001f)
        assertTrue(sheet.expanded)
    }
}
