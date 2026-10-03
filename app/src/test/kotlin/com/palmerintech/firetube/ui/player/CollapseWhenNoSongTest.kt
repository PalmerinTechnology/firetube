package com.palmerintech.firetube.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CollapseWhenNoSongTest {

    @get:Rule val compose = createComposeRule()

    private lateinit var sheet: PlayerSheetState
    private var hasSong by mutableStateOf(false)
    private var connected by mutableStateOf(false)

    /** The app as it starts after being restored with Now Playing open: no song, not connected. */
    private fun restoredOpen() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            sheet = rememberPlayerSheetState(initiallyExpanded = true)
            CollapseWhenNoSong(sheet, hasSong, connected, graceMs = 3_000)
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun advance(ms: Long) = compose.mainClock.advanceTimeBy(ms)

    /** Changes player state the way the app does: on the main thread, then lets composition see it. */
    private fun set(block: () -> Unit) {
        compose.runOnUiThread { block(); Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun staysOpenWhileTheRestoredQueueComesBack() {
        restoredOpen()
        advance(5_000) // not connected yet: nothing to decide
        assertTrue(sheet.expanded)
        set { connected = true }
        advance(1_000)
        set { hasSong = true } // the service restored the queue within the grace period
        advance(5_000)
        assertTrue(sheet.expanded)
    }

    @Test
    fun closesWhenNothingIsRestored() {
        restoredOpen()
        set { connected = true }
        advance(2_000)
        assertTrue("waits out the grace period", sheet.expanded)
        advance(2_000)
        assertFalse(sheet.expanded)
    }

    @Test
    fun closesAtOnceWhenTheQueueRunsOut() {
        restoredOpen()
        set { connected = true }
        set { hasSong = true }
        advance(100)
        set { hasSong = false }
        advance(100)
        assertFalse(sheet.expanded)
    }
}
