package com.palmerintech.firetube.widget

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.player.MediaItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WidgetModelTest {

    private val song = Track("abc", "Song", "Artist", 200, "https://i.ytimg.com/vi/abc/hq.jpg")
    private val other = Track("def", "Other", "Someone", 180, null)

    /** A player frozen in one state. */
    private class StubPlayer(private val state: State) : SimpleBasePlayer(Looper.getMainLooper()) {
        override fun getState() = state
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = Futures.immediateVoidFuture()
    }

    private fun player(
        queue: List<Track>,
        index: Int = 0,
        playWhenReady: Boolean = false,
        playbackState: Int = Player.STATE_READY,
    ): Player {
        val state = SimpleBasePlayer.State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(queue.map { SimpleBasePlayer.MediaItemData.Builder(it.id).setMediaItem(MediaItems.of(it)).build() })
            .setCurrentMediaItemIndex(if (queue.isEmpty()) 0 else index)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (queue.isEmpty()) Player.STATE_IDLE else playbackState)
            .build()
        return StubPlayer(state)
    }

    @Test
    fun emptyQueueIsTapToStart() {
        assertEquals(WidgetModel.Empty, WidgetModel.of(player(emptyList())))
        assertNull(WidgetModel.Empty.track)
    }

    @Test
    fun playingSongShowsPause() {
        val model = WidgetModel.of(player(listOf(song, other), playWhenReady = true))
        assertEquals(song, model.track)
        assertTrue(model.playing)
        assertTrue(model.canNext)
        assertTrue(model.canPrevious)
    }

    @Test
    fun bufferingStillShowsPause() {
        // Pressing play shouldn't flip back to "play" while the stream loads.
        assertTrue(WidgetModel.of(player(listOf(song), playWhenReady = true, playbackState = Player.STATE_BUFFERING)).playing)
    }

    @Test
    fun pausedOrFinishedShowsPlay() {
        assertFalse(WidgetModel.of(player(listOf(song), playWhenReady = false)).playing)
        assertFalse(WidgetModel.of(player(listOf(song), playWhenReady = true, playbackState = Player.STATE_ENDED)).playing)
        assertFalse(WidgetModel.of(player(listOf(song), playWhenReady = true, playbackState = Player.STATE_IDLE)).playing)
    }

    @Test
    fun lastSongCantSkipAhead() {
        val model = WidgetModel.of(player(listOf(song, other), index = 1, playWhenReady = true))
        assertEquals(other, model.track)
        assertFalse(model.canNext)
        assertTrue(model.canPrevious)
    }

    @Test
    fun idleKeepsTheSongButOnlyPlayWorks() {
        val idle = WidgetModel(song, playing = true, canPrevious = true, canNext = true).idle()
        assertEquals(WidgetModel(song), idle)
    }

    @Test
    fun sizesByWidth() {
        assertEquals(WidgetSize.Compact, WidgetSize.forWidth(140))
        assertEquals(WidgetSize.Compact, WidgetSize.forWidth(199))
        assertEquals(WidgetSize.Medium, WidgetSize.forWidth(200))
        assertEquals(WidgetSize.Medium, WidgetSize.forWidth(279))
        assertEquals(WidgetSize.Wide, WidgetSize.forWidth(280))
        assertEquals(WidgetSize.Wide, WidgetSize.forWidth(400))
        // Launcher didn't say: the default (3–4 cell) size.
        assertEquals(WidgetSize.Medium, WidgetSize.forWidth(0))
    }

    @Test
    fun breakpointsStartAtTheSmallestResizableWidth() {
        val widths = WidgetSize.breakpoints().mapValues { it.value.width }
        assertEquals(mapOf(WidgetSize.Compact to 140f, WidgetSize.Medium to 200f, WidgetSize.Wide to 280f), widths)
        // Each breakpoint picks the same layout as the width-based choice for older launchers.
        WidgetSize.breakpoints().forEach { (size, dp) -> assertEquals(size, WidgetSize.forWidth(dp.width.toInt())) }
    }

    @Test
    fun commandsWithoutARunningPlayer() {
        assertEquals(WidgetCommand.Play, WidgetCommand.from(NowPlayingWidget.ACTION_PLAY, playerRunning = false))
        assertNull(WidgetCommand.from(NowPlayingWidget.ACTION_PAUSE, playerRunning = false))
        assertNull(WidgetCommand.from(NowPlayingWidget.ACTION_NEXT, playerRunning = false))
        assertNull(WidgetCommand.from(NowPlayingWidget.ACTION_PREVIOUS, playerRunning = false))
    }

    @Test
    fun commandsWithARunningPlayer() {
        assertEquals(WidgetCommand.Play, WidgetCommand.from(NowPlayingWidget.ACTION_PLAY, playerRunning = true))
        assertEquals(WidgetCommand.Pause, WidgetCommand.from(NowPlayingWidget.ACTION_PAUSE, playerRunning = true))
        assertEquals(WidgetCommand.Next, WidgetCommand.from(NowPlayingWidget.ACTION_NEXT, playerRunning = true))
        assertEquals(WidgetCommand.Previous, WidgetCommand.from(NowPlayingWidget.ACTION_PREVIOUS, playerRunning = true))
        // The launcher's own broadcasts are left to AppWidgetProvider.
        assertNull(WidgetCommand.from("android.appwidget.action.APPWIDGET_UPDATE", playerRunning = true))
        assertNull(WidgetCommand.from(null, playerRunning = true))
    }
}
