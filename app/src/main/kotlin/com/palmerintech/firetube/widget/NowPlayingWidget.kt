package com.palmerintech.firetube.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.UnstableApi
import com.palmerintech.firetube.FireTubeApp
import com.palmerintech.firetube.R
import com.palmerintech.firetube.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * The home-screen now-playing widget. [WidgetUpdater] (inside PlaybackService) pushes every state
 * change; this class draws the widget when the launcher asks (added, resized, after a reboot) and
 * turns button presses into commands on the app's player connection.
 */
@UnstableApi
class NowPlayingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = redraw(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = redraw(context)

    override fun onReceive(context: Context, intent: Intent) {
        val command = WidgetCommand.from(intent.action, playerRunning = live != null)
            ?: return super.onReceive(context, intent)
        val player = (context.applicationContext as FireTubeApp).container.player
        when (command) {
            WidgetCommand.Play -> player.resume()
            WidgetCommand.Pause -> player.pause()
            WidgetCommand.Next -> player.next()
            WidgetCommand.Previous -> player.previous()
        }
    }

    /** With the player running, show its state; otherwise the saved queue, paused (play resumes it). */
    private fun redraw(context: Context) {
        live?.let { (model, art) -> return push(context, model, art) }
        val container = (context.applicationContext as FireTubeApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                val saved = container.queueStore.load()?.takeIf { it.tracks.isNotEmpty() }
                val track = saved?.let { it.tracks[it.index.coerceIn(0, it.tracks.lastIndex)].toTrack() }
                // The service may have started meanwhile; its state wins.
                if (live == null) push(context, WidgetModel(track), art = null)
            } finally {
                pending.finish()
            }
        }
    }

    /** The running player's latest state and artwork, set by [WidgetUpdater]; null when it isn't running. */
    data class Live(val model: WidgetModel, val art: Bitmap?)

    companion object {
        const val ACTION_PLAY = "com.palmerintech.firetube.widget.PLAY"
        const val ACTION_PAUSE = "com.palmerintech.firetube.widget.PAUSE"
        const val ACTION_NEXT = "com.palmerintech.firetube.widget.NEXT"
        const val ACTION_PREVIOUS = "com.palmerintech.firetube.widget.PREVIOUS"

        @Volatile
        var live: Live? = null

        /** Redraws every placed widget. Cheap when none are placed. */
        fun push(context: Context, model: WidgetModel, art: Bitmap?) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))
            if (ids.isEmpty()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.updateAppWidget(ids, responsiveViews(context, model, art))
            } else {
                // Older launchers don't pick a layout by size; choose one from each widget's width.
                for (id in ids) {
                    val width = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                    manager.updateAppWidget(id, views(context, model, art, WidgetSize.forWidth(width)))
                }
            }
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun responsiveViews(context: Context, model: WidgetModel, art: Bitmap?) =
            RemoteViews(WidgetSize.breakpoints().entries.associate { (size, dp) -> dp to views(context, model, art, size) })

        @VisibleForTesting
        internal fun views(context: Context, model: WidgetModel, art: Bitmap?, size: WidgetSize): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_now_playing)
            val track = model.track
            if (art != null && track != null) views.setImageViewBitmap(R.id.widget_art, art)
            else views.setImageViewResource(R.id.widget_art, R.drawable.widget_art_placeholder)

            if (track == null) {
                views.setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
                views.setTextViewText(R.id.widget_artist, context.getString(R.string.widget_tap_to_start))
                views.setViewVisibility(R.id.widget_text, View.VISIBLE)
                for (id in listOf(R.id.widget_previous, R.id.widget_play_pause, R.id.widget_next)) views.setViewVisibility(id, View.GONE)
                views.setOnClickPendingIntent(android.R.id.background, openApp(context, nowPlaying = false))
                return views
            }

            views.setTextViewText(R.id.widget_title, track.title)
            views.setTextViewText(R.id.widget_artist, track.artist)
            // Hidden but still laid out on the compact size, so the buttons stay pushed to the end.
            views.setViewVisibility(R.id.widget_text, if (size.showsText) View.VISIBLE else View.INVISIBLE)
            views.setOnClickPendingIntent(android.R.id.background, openApp(context, nowPlaying = true))

            views.setViewVisibility(R.id.widget_play_pause, View.VISIBLE)
            views.setImageViewResource(R.id.widget_play_pause, if (model.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
            views.setContentDescription(
                R.id.widget_play_pause,
                context.getString(if (model.playing) R.string.widget_pause else R.string.widget_play),
            )
            views.setOnClickPendingIntent(R.id.widget_play_pause, control(context, if (model.playing) ACTION_PAUSE else ACTION_PLAY))

            views.setViewVisibility(R.id.widget_next, View.VISIBLE)
            views.button(R.id.widget_next, model.canNext)
            views.setOnClickPendingIntent(R.id.widget_next, control(context, ACTION_NEXT))

            views.setViewVisibility(R.id.widget_previous, if (size.showsPrevious) View.VISIBLE else View.GONE)
            views.button(R.id.widget_previous, model.canPrevious)
            views.setOnClickPendingIntent(R.id.widget_previous, control(context, ACTION_PREVIOUS))
            return views
        }

        /** Dims a button that can't do anything right now. */
        private fun RemoteViews.button(id: Int, enabled: Boolean) {
            setBoolean(id, "setEnabled", enabled)
            setInt(id, "setImageAlpha", if (enabled) 255 else 97)
        }

        /** A button press, delivered back to this (non-exported) receiver. */
        @VisibleForTesting
        internal fun control(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(action).setClass(context, NowPlayingWidget::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun openApp(context: Context, nowPlaying: Boolean): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
            if (nowPlaying) intent.action = MainActivity.ACTION_OPEN_PLAYER
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
