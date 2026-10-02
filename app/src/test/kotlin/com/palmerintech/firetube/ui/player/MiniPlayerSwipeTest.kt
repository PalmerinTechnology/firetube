package com.palmerintech.firetube.ui.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.player.PlayerUiState
import org.junit.Assert.assertEquals
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
    private var opened = 0

    private fun show(hasNext: Boolean = true, hasPrevious: Boolean = true) = compose.setContent {
        MaterialTheme {
            MiniPlayer(
                PlayerUiState(current = track, hasNext = hasNext, hasPrevious = hasPrevious),
                onTogglePlay = {}, onNext = { next++ }, onPrevious = { previous++ }, onOpen = { opened++ },
                modifier = Modifier.testTag(TAG),
            )
        }
    }

    private fun swipe(block: androidx.compose.ui.test.TouchInjectionScope.() -> Unit) {
        compose.onNodeWithTag(TAG).performTouchInput(block)
        compose.waitForIdle()
    }

    @Test
    fun swipeLeftPlaysTheNextTrack() {
        show()
        swipe { swipeLeft() }
        assertEquals(listOf(1, 0, 0), listOf(next, previous, opened))
    }

    @Test
    fun swipeRightPlaysThePreviousTrack() {
        show()
        swipe { swipeRight() }
        assertEquals(listOf(0, 1, 0), listOf(next, previous, opened))
    }

    @Test
    fun swipingPastTheEndOfTheQueueSpringsBackWithoutSkipping() {
        show(hasNext = false, hasPrevious = false)
        swipe { swipeLeft() }
        swipe { swipeRight() }
        assertEquals(listOf(0, 0, 0), listOf(next, previous, opened))
    }

    @Test
    fun swipeUpOpensTheFullPlayer() {
        show()
        swipe { swipeUp() }
        assertEquals(listOf(0, 0, 1), listOf(next, previous, opened))
    }

    @Test
    fun tapStillOpensTheFullPlayer() {
        show()
        compose.onNodeWithTag(TAG).performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
    }

    private companion object {
        const val TAG = "mini-player"
    }
}
