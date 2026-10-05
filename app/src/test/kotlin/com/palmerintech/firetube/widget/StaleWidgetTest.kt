package com.palmerintech.firetube.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.ImageButton
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.palmerintech.firetube.R
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.player.QueueStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** The widget after FireTube's process died mid-song, when nothing told it playback stopped. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StaleWidgetTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val song = Track("abc", "Song", "Artist", 200, null)
    private val other = Track("def", "Other", "Someone", 180, null)
    private val store = QueueStore(context)

    @Before
    fun setUp() {
        NowPlayingWidget.live = null
    }

    @After
    fun tearDown() {
        File(context.filesDir, "queue.json").delete()
    }

    @Test
    fun idleModelIsTheSavedSongPaused() = runBlocking {
        store.saveNow(listOf(song, other), index = 1, positionMs = 30_000)
        assertEquals(WidgetModel(other), NowPlayingWidget.idleModel(store))
    }

    @Test
    fun idleModelClampsABadIndex() = runBlocking {
        store.saveNow(listOf(song, other), index = 7, positionMs = 0)
        assertEquals(WidgetModel(other), NowPlayingWidget.idleModel(store))
    }

    @Test
    fun idleModelWithNothingSavedIsTapToStart() = runBlocking {
        assertEquals(WidgetModel.Empty, NowPlayingWidget.idleModel(store))
        store.saveNow(emptyList(), index = 0, positionMs = 0)
        assertEquals(WidgetModel.Empty, NowPlayingWidget.idleModel(store))
    }

    @Test
    fun aNewOrRebootedWidgetShowsTheSavedSongPaused() {
        store.saveNow(listOf(song, other), index = 0, positionMs = 0)
        val host = shadowOf(AppWidgetManager.getInstance(context))
        // Adding the widget (like a reboot or app update) sends onUpdate.
        val id = host.createWidget(NowPlayingWidget::class.java, R.layout.widget_now_playing)
        // The layout's own sample content says "FireTube" / "Play"; wait for the real redraw.
        awaitWidget(id, title = "Song", playButton = "Play")
    }

    @Test
    fun pressingPauseOnAStaleWidgetRedrawsItPaused() {
        store.saveNow(listOf(song, other), index = 0, positionMs = 0)
        val host = shadowOf(AppWidgetManager.getInstance(context))
        val id = host.createWidget(NowPlayingWidget::class.java, R.layout.widget_now_playing)
        awaitWidget(id, title = "Song", playButton = "Play") // onUpdate's redraw, so it isn't mistaken for the one under test
        // What the widget showed when the process died.
        NowPlayingWidget.push(context, WidgetModel(song, playing = true, canPrevious = true, canNext = true), art = null)
        awaitWidget(id, title = "Song", playButton = "Pause")

        context.sendBroadcast(Intent(NowPlayingWidget.ACTION_PAUSE).setClass(context, NowPlayingWidget::class.java))

        awaitWidget(id, title = "Song", playButton = "Play")
        val view = host.getViewFor(id)
        assertEquals("Song", view.findViewById<TextView>(R.id.widget_title).text.toString())
        assertFalse(view.findViewById<ImageButton>(R.id.widget_next).isEnabled)
        assertTrue(view.findViewById<ImageButton>(R.id.widget_play_pause).isEnabled)
    }

    @Test
    fun serviceStartingDuringTheQueueLoadWins() {
        val host = shadowOf(AppWidgetManager.getInstance(context))
        val id = host.createWidget(NowPlayingWidget::class.java, R.layout.widget_now_playing)
        awaitWidget(id, title = "FireTube", playButton = "Play") // nothing saved: onUpdate's redraw changes nothing visible
        val loaded = CompletableDeferred<WidgetModel>()
        val redraw = CoroutineScope(Dispatchers.Default).launch { NowPlayingWidget.drawIdle(context) { loaded.await() } }

        // The service starts while the saved queue is still loading, and draws its state (on main, as WidgetUpdater does).
        val playing = WidgetModel(song, playing = true, canPrevious = true, canNext = true)
        NowPlayingWidget.live = NowPlayingWidget.Live(playing, art = null)
        NowPlayingWidget.push(context, playing, art = null)
        loaded.complete(WidgetModel(other))

        awaitCompletion(redraw)
        awaitWidget(id, title = "Song", playButton = "Pause")
    }

    @Test
    fun idleDrawLandsWithoutAPlayer() {
        // Something saved, so onUpdate's own redraw is visible and can be waited out before ours.
        store.saveNow(listOf(song), index = 0, positionMs = 0)
        val host = shadowOf(AppWidgetManager.getInstance(context))
        val id = host.createWidget(NowPlayingWidget::class.java, R.layout.widget_now_playing)
        awaitWidget(id, title = "Song", playButton = "Play")
        val redraw = CoroutineScope(Dispatchers.Default).launch { NowPlayingWidget.drawIdle(context) { WidgetModel(other) } }
        // The draw happens on main, but the coroutine finishes back on Default: wait for both.
        awaitCompletion(redraw)
        awaitWidget(id, title = "Other", playButton = "Play")
    }

    /** Runs the main looper (where the draw happens) until [job] finishes, which it must within 5s. */
    private fun awaitCompletion(job: Job) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!job.isCompleted && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("redraw didn't finish", job.isCompleted)
    }

    /** Redraws without a player load the saved queue in the background; wait for one to land. */
    private fun awaitWidget(id: Int, title: String, playButton: String) {
        val host = shadowOf(AppWidgetManager.getInstance(context))
        fun current(): Pair<String, String> {
            val view = host.getViewFor(id)
            return view.findViewById<TextView>(R.id.widget_title).text.toString() to
                view.findViewById<ImageButton>(R.id.widget_play_pause).contentDescription.toString()
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (current() != (title to playButton) && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertEquals(title to playButton, current())
    }
}
