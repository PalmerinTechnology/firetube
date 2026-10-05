package com.palmerintech.firetube.widget

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.palmerintech.firetube.R
import com.palmerintech.firetube.extractor.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NowPlayingWidgetViewsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val song = Track("abc", "Song", "Artist", 200, null)

    /** Inflates the widget the way a launcher would. */
    private fun render(model: WidgetModel, size: WidgetSize = WidgetSize.Wide): View =
        NowPlayingWidget.views(context, model, art = null, size).apply(context, FrameLayout(context))

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun View.visibility(id: Int) = findViewById<View>(id).visibility

    @Test
    fun nothingQueuedInvitesATap() {
        val view = render(WidgetModel.Empty)
        assertEquals("FireTube", view.text(R.id.widget_title))
        assertEquals("Tap to start", view.text(R.id.widget_artist))
        for (id in listOf(R.id.widget_previous, R.id.widget_play_pause, R.id.widget_next)) {
            assertEquals(View.GONE, view.visibility(id))
        }
    }

    @Test
    fun showsTheSongWithControls() {
        val view = render(WidgetModel(song, playing = true, canPrevious = true, canNext = true))
        assertEquals("Song", view.text(R.id.widget_title))
        assertEquals("Artist", view.text(R.id.widget_artist))
        assertEquals("Pause", view.findViewById<ImageButton>(R.id.widget_play_pause).contentDescription)
        for (id in listOf(R.id.widget_previous, R.id.widget_play_pause, R.id.widget_next)) {
            assertEquals(View.VISIBLE, view.visibility(id))
        }
    }

    @Test
    fun pausedShowsPlay() {
        val view = render(WidgetModel(song, playing = false))
        assertEquals("Play", view.findViewById<ImageButton>(R.id.widget_play_pause).contentDescription)
    }

    @Test
    fun narrowerSizesDropPreviousThenText() {
        val model = WidgetModel(song, playing = true, canPrevious = true, canNext = true)
        render(model, WidgetSize.Medium).let {
            assertEquals(View.GONE, it.visibility(R.id.widget_previous))
            assertEquals(View.VISIBLE, it.visibility(R.id.widget_text))
        }
        render(model, WidgetSize.Compact).let {
            assertEquals(View.GONE, it.visibility(R.id.widget_previous))
            assertEquals(View.INVISIBLE, it.visibility(R.id.widget_text))
            assertEquals(View.VISIBLE, it.visibility(R.id.widget_play_pause))
            assertEquals(View.VISIBLE, it.visibility(R.id.widget_next))
        }
    }

    @Test
    fun screenReadersHearTheSongEvenWhenTheTitleIsHidden() {
        val view = render(WidgetModel(song, playing = true), WidgetSize.Compact)
        assertEquals("Song by Artist. Opens Now Playing", view.contentDescription)
        assertEquals("Song. Opens Now Playing", render(WidgetModel(song.copy(artist = "")), WidgetSize.Compact).contentDescription)
        assertEquals("FireTube. Tap to start", render(WidgetModel.Empty, WidgetSize.Compact).contentDescription)
    }

    @Test
    fun skipButtonsAreDisabledWhenTheyCantDoAnything() {
        val view = render(WidgetModel(song).idle())
        assertFalse(view.findViewById<View>(R.id.widget_next).isEnabled)
        assertFalse(view.findViewById<View>(R.id.widget_previous).isEnabled)
        assertTrue(view.findViewById<View>(R.id.widget_play_pause).isEnabled)
    }

    @Test
    fun tappingButtonsSendsTheirCommands() {
        val app = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        fun tap(model: WidgetModel, id: Int): String? {
            app.clearBroadcastIntents()
            render(model).findViewById<View>(id).performClick()
            return app.broadcastIntents.singleOrNull()?.action
        }
        val playing = WidgetModel(song, playing = true, canPrevious = true, canNext = true)
        assertEquals(NowPlayingWidget.ACTION_PAUSE, tap(playing, R.id.widget_play_pause))
        assertEquals(NowPlayingWidget.ACTION_PLAY, tap(playing.copy(playing = false), R.id.widget_play_pause))
        assertEquals(NowPlayingWidget.ACTION_NEXT, tap(playing, R.id.widget_next))
        assertEquals(NowPlayingWidget.ACTION_PREVIOUS, tap(playing, R.id.widget_previous))
    }

    @Test
    fun buttonsBroadcastToTheWidgetReceiver() {
        for (action in listOf(NowPlayingWidget.ACTION_PLAY, NowPlayingWidget.ACTION_PAUSE, NowPlayingWidget.ACTION_NEXT, NowPlayingWidget.ACTION_PREVIOUS)) {
            val pending = shadowOf(NowPlayingWidget.control(context, action))
            assertTrue(pending.isBroadcastIntent)
            assertTrue(pending.isImmutable)
            assertEquals(action, pending.savedIntent.action)
            assertEquals(ComponentName(context, NowPlayingWidget::class.java), pending.savedIntent.component)
        }
    }
}
