package com.palmerintech.firetube.player

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.palmerintech.firetube.data.LibraryRepository
import com.palmerintech.firetube.extractor.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Measures how long each song actually plays, for Your stats. Time only runs while the player is
 * really playing, so pauses, buffering and SponsorBlock jumps (seeks) aren't counted; gapless and
 * autoplay transitions close one listen and open the next. Pure state: [ListenTracker] drives it.
 */
class ListenCounter(
    private val elapsed: () -> Long,
    private val wallClock: () -> Long,
) {
    /** A song's listen so far. [startedAt] is when it first made sound, not when it was queued. */
    data class Listen(val track: Track, val startedAt: Long, val msListened: Long)

    private var track: Track? = null
    private var startedAt = 0L
    private var banked = 0L
    /** [elapsed] when audio last started, or -1 while not playing. */
    private var playingSince = -1L

    /** A new item (or the same one repeating) became current. Returns the previous listen if it counts. */
    fun onItem(next: Track?, isPlaying: Boolean): Listen? {
        val done = current()
        track = next
        startedAt = 0L
        banked = 0L
        playingSince = -1L
        if (next != null && isPlaying) onPlaying(true)
        return done?.takeIf(::counts)
    }

    /** Playback started or stopped. On stop, returns the listen so far if it counts, so it can be saved. */
    fun onPlaying(isPlaying: Boolean): Listen? {
        if (track == null) return null
        if (isPlaying) {
            if (playingSince < 0) {
                if (startedAt == 0L) startedAt = wallClock()
                playingSince = elapsed()
            }
            return null
        }
        bank()
        return current()?.takeIf(::counts)
    }

    /** The listen so far (time up to now included), or null if nothing has played yet. */
    fun current(): Listen? {
        val t = track ?: return null
        if (startedAt == 0L) return null
        val running = if (playingSince >= 0) elapsed() - playingSince else 0L
        return Listen(t, startedAt, banked + running)
    }

    private fun bank() {
        if (playingSince >= 0) banked += elapsed() - playingSince
        playingSince = -1L
    }

    companion object {
        /** A play counts after 30 seconds, or half the song if it's shorter than a minute. */
        const val MIN_LISTEN_MS = 30_000L

        fun threshold(durationSeconds: Long): Long =
            if (durationSeconds > 0) minOf(MIN_LISTEN_MS, durationSeconds * 1000 / 2) else MIN_LISTEN_MS

        fun counts(listen: Listen): Boolean = listen.msListened >= threshold(listen.track.durationSeconds)
    }
}

/** Feeds the session's player into a [ListenCounter] and saves listens that count. */
class ListenTracker(private val player: Player, private val library: LibraryRepository) : Player.Listener {
    private val counter = ListenCounter(SystemClock::elapsedRealtime, System::currentTimeMillis)

    // Not the service's scope: the last save is launched as the service is destroyed.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        save(counter.onItem(mediaItem?.let(MediaItems::trackOf), player.isPlaying))
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        save(counter.onPlaying(isPlaying))
    }

    /** Saves what's been heard of the current song, e.g. before the service goes away. */
    fun flush() {
        save(counter.current()?.takeIf(ListenCounter::counts))
    }

    private fun save(listen: ListenCounter.Listen?) {
        listen ?: return
        scope.launch { runCatching { library.recordListen(listen.track, listen.startedAt, listen.msListened) } }
    }
}
