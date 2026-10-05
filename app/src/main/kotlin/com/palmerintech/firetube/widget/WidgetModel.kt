package com.palmerintech.firetube.widget

import android.util.SizeF
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.player.MediaItems

/** What the home-screen widget shows. [track] null = nothing queued ("Tap to start"). */
data class WidgetModel(
    val track: Track?,
    /** Playing, or about to (buffering): shows pause. Paused, stopped or finished: shows play. */
    val playing: Boolean = false,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
) {
    /** Nothing to skip to without a running player; play still works (it resumes the saved queue). */
    fun idle() = copy(playing = false, canPrevious = false, canNext = false)

    companion object {
        val Empty = WidgetModel(track = null)

        @UnstableApi
        fun of(player: Player): WidgetModel {
            val item = player.currentMediaItem ?: return Empty
            return WidgetModel(
                track = MediaItems.trackOf(item),
                // Same rule as the media notification, so the two never disagree mid-buffer.
                playing = !Util.shouldShowPlayButton(player),
                // Previous restarts the song when there's nothing before it, so it always does something.
                canPrevious = true,
                canNext = player.hasNextMediaItem(),
            )
        }
    }
}

/** Widget layouts by width; previous only fits on the widest, the title only from medium up. */
enum class WidgetSize(val minWidthDp: Int) {
    Compact(0), Medium(200), Wide(280);

    val showsText get() = this != Compact
    val showsPrevious get() = this == Wide

    companion object {
        /** For launchers that report a width (pre-Android 12); 0 means unknown, so assume the default size. */
        fun forWidth(widthDp: Int): WidgetSize = when {
            widthDp <= 0 -> Medium
            else -> entries.last { widthDp >= it.minWidthDp }
        }

        /** Android 12+: the launcher picks the largest of these that fits. */
        fun breakpoints(): Map<WidgetSize, SizeF> =
            entries.associateWith { SizeF(maxOf(it.minWidthDp, COMPACT_MIN_WIDTH_DP).toFloat(), MIN_HEIGHT_DP) }

        /** Matches minResizeWidth in xml/now_playing_widget_info.xml. */
        private const val COMPACT_MIN_WIDTH_DP = 140
        private const val MIN_HEIGHT_DP = 40f
    }
}

/** What a button press on the widget should do. */
enum class WidgetCommand {
    Play, Pause, Next, Previous;

    companion object {
        /**
         * The command for a widget broadcast, or null to ignore it. Without a running player only
         * play does anything (it starts the service and resumes the saved queue); skipping or pausing
         * a queue that isn't loaded would just start the service for nothing.
         */
        @UnstableApi
        fun from(action: String?, playerRunning: Boolean): WidgetCommand? = when (action) {
            NowPlayingWidget.ACTION_PLAY -> Play
            NowPlayingWidget.ACTION_PAUSE -> Pause.takeIf { playerRunning }
            NowPlayingWidget.ACTION_NEXT -> Next.takeIf { playerRunning }
            NowPlayingWidget.ACTION_PREVIOUS -> Previous.takeIf { playerRunning }
            else -> null
        }
    }
}
