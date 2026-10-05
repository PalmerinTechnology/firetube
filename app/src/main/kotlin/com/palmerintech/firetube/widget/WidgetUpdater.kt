package com.palmerintech.firetube.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.size.Scale
import coil3.toBitmap
import coil3.transform.RoundedCornersTransformation
import com.palmerintech.firetube.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps [NowPlayingWidget] in step with the player. Driven by player events only — nothing polls —
 * and the widget is redrawn only when what it shows changes (not on every position update).
 */
@UnstableApi
class WidgetUpdater(private val context: Context, private val player: Player, private val scope: CoroutineScope) {

    private var model: WidgetModel? = null
    private var artUrl: String? = null
    private var art: Bitmap? = null
    private var artJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(*RELEVANT_EVENTS)) refresh()
        }
    }

    fun start() {
        player.addListener(listener)
        refresh()
    }

    /** The service is going away: leave the widget showing the song, paused, with play resuming it. */
    fun stop() {
        player.removeListener(listener)
        artJob?.cancel()
        NowPlayingWidget.live = null
        NowPlayingWidget.push(context, (model ?: WidgetModel.Empty).idle(), art)
    }

    private fun refresh() {
        val next = WidgetModel.of(player)
        if (next == model) return
        model = next
        val url = next.track?.thumbnailUrl
        if (url != artUrl) {
            artUrl = url
            art = null
            artJob?.cancel()
            if (url != null) artJob = scope.launch { loadArt(url)?.let { if (artUrl == url) { art = it; publish() } } }
        }
        publish()
    }

    private fun publish() {
        val model = model ?: return
        NowPlayingWidget.live = NowPlayingWidget.Live(model, art)
        NowPlayingWidget.push(context, model, art)
    }

    /** A small, square, rounded, software bitmap (RemoteViews can't carry hardware bitmaps). */
    private suspend fun loadArt(url: String): Bitmap? {
        val res = context.resources
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(res.getDimensionPixelSize(R.dimen.widget_art_size))
            .scale(Scale.FILL)
            .allowHardware(false)
            .transformations(RoundedCornersTransformation(res.getDimension(R.dimen.widget_art_radius)))
            .build()
        return (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
    }

    private companion object {
        val RELEVANT_EVENTS = intArrayOf(
            Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_MEDIA_METADATA_CHANGED,
            Player.EVENT_TIMELINE_CHANGED,
            Player.EVENT_PLAY_WHEN_READY_CHANGED,
            Player.EVENT_PLAYBACK_STATE_CHANGED,
            Player.EVENT_REPEAT_MODE_CHANGED,
            Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
        )
    }
}
