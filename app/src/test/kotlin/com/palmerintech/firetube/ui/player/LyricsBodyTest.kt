package com.palmerintech.firetube.ui.player

import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.palmerintech.firetube.lyrics.LrcLine
import com.palmerintech.firetube.lyrics.LyricsResult
import com.palmerintech.firetube.ui.Load
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LyricsBodyTest {

    @get:Rule val compose = createComposeRule()

    private val lines = (0 until 40).map { LrcLine(it * 5_000L, "Line $it") }
    private val seeks = mutableListOf<Long>()
    private var retries = 0

    private fun show(load: Load<LyricsResult>, position: () -> Long = { 0 }) = compose.setContent {
        MaterialTheme {
            LyricsBody(load, position, onSeek = { seeks += it }, onRetry = { retries++ }, modifier = Modifier.height(600.dp))
        }
    }

    @Test
    fun `highlights the line playing and follows playback`() {
        var position by mutableLongStateOf(6_000L)
        show(Load.Ready(LyricsResult.Synced(lines))) { position }
        compose.onNodeWithText("Line 1").assertIsSelected()
        compose.onNodeWithText("Line 0").assertIsNotSelected()

        // Far down the song: the list scrolls so the current line is on screen.
        position = 30 * 5_000L + 100
        compose.waitForIdle()
        compose.onNodeWithText("Line 30").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithText("Line 1").assertDoesNotExist()
    }

    @Test
    fun `tapping a line seeks to it`() {
        show(Load.Ready(LyricsResult.Synced(lines)))
        compose.onNodeWithText("Line 2").performClick()
        assertEquals(listOf(10_000L), seeks)
    }

    @Test
    fun `lines say what tapping does`() {
        show(Load.Ready(LyricsResult.Synced(lines)))
        val label = compose.onNodeWithText("Line 2").fetchSemanticsNode().config[SemanticsActions.OnClick].label
        assertEquals("Jump to this line", label)
    }

    @Test
    fun `the position estimate runs between reports while playing, capped`() {
        assertEquals(10_000L, estimatePosition(10_000, reportedAt = 500, now = 700, playing = false))
        assertEquals(10_200L, estimatePosition(10_000, reportedAt = 500, now = 700, playing = true))
        assertEquals(10_750L, estimatePosition(10_000, reportedAt = 500, now = 5_000, playing = true)) // stalled
        assertEquals(10_000L, estimatePosition(10_000, reportedAt = 500, now = 400, playing = true))
    }

    @Test
    fun `the position estimate follows the playback speed`() {
        assertEquals(10_400L, estimatePosition(10_000, reportedAt = 500, now = 700, playing = true, speed = 2f))
        assertEquals(10_150L, estimatePosition(10_000, reportedAt = 500, now = 700, playing = true, speed = 0.75f))
        assertEquals(10_250L, estimatePosition(10_000, reportedAt = 500, now = 700, playing = true, speed = 1.25f))
        // The cap is on time since the report, then scaled: at 2x, 750 ms of waiting is 1.5 s of song.
        assertEquals(11_500L, estimatePosition(10_000, reportedAt = 500, now = 5_000, playing = true, speed = 2f))
        assertEquals(10_000L, estimatePosition(10_000, reportedAt = 500, now = 700, playing = false, speed = 2f))
    }

    @Test
    fun `instrumental gaps show a note`() {
        show(Load.Ready(LyricsResult.Synced(listOf(LrcLine(0, "Sing"), LrcLine(4_000, "")))))
        compose.onNodeWithText("♪").assertIsDisplayed()
    }

    @Test
    fun `plain lyrics, nothing found and failures`() {
        var load by mutableStateOf<Load<LyricsResult>>(Load.Ready(LyricsResult.Plain("First line\n\nSecond verse")))
        compose.setContent { MaterialTheme { LyricsBody(load, { 0L }, {}, { retries++ }) } }
        compose.onNodeWithText("First line").assertIsDisplayed()
        compose.onNodeWithText("Second verse").assertIsDisplayed()

        load = Load.Ready(LyricsResult.NotFound)
        compose.onNodeWithText("No lyrics found for this song.").assertIsDisplayed()

        load = Load.Failed("You're offline.")
        compose.onNodeWithText("You're offline.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }
}
