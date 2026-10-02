package com.palmerintech.firetube.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.palmerintech.firetube.extractor.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/** UI-facing player state. */
data class PlayerUiState(
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
)

/**
 * Connects the UI to [PlaybackService] through a [MediaController] and exposes its state as a
 * flow. Commands issued before the connection is ready are queued and run once it is.
 */
@UnstableApi
class PlayerConnection(private val context: Context, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private var ticker: Job? = null

    private var connecting = false

    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        scope.launch {
            try {
                val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
                val c = MediaController.Builder(context, token).buildAsync().await()
                controller = c
                c.addListener(listener)
                publish(c)
                pending.forEach { it(c) }
                pending.clear()
            } catch (e: Exception) {
                // Drop queued commands (they'd replay stale later); the next command reconnects.
                pending.clear()
                Timber.w(e, "Couldn't connect to the player")
            } finally {
                connecting = false
            }
        }
    }

    fun release() {
        ticker?.cancel()
        controller?.release()
        controller = null
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let(block) ?: run {
            pending += block
            connect()
        }
    }

    // --- commands ---

    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) = withController { c ->
        if (tracks.isEmpty()) return@withController
        c.shuffleModeEnabled = shuffle
        val start = if (shuffle) tracks.indices.random() else startIndex.coerceIn(tracks.indices)
        c.setMediaItems(tracks.map(MediaItems::of), start, 0)
        c.prepare()
        c.play()
    }

    fun playNext(track: Track) = withController { c ->
        if (c.mediaItemCount == 0) return@withController play(listOf(track))
        c.addMediaItem(c.currentMediaItemIndex + 1, MediaItems.of(track))
    }

    fun enqueue(tracks: List<Track>) = withController { c ->
        if (c.mediaItemCount == 0) return@withController play(tracks)
        c.addMediaItems(tracks.map(MediaItems::of))
    }

    fun togglePlay() = withController { c ->
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { it.seekToPrevious() }

    /** Always changes track (unlike [previous], which restarts the song when it's past the start). */
    fun previousTrack() = withController { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0) }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun skipTo(index: Int) = withController { it.seekToDefaultPosition(index); it.play() }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun moveQueueItem(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }
    fun removeQueueItem(index: Int) = withController { it.removeMediaItem(index) }

    // --- state ---

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
        }
    }

    private fun publish(p: Player) {
        val queue = if (p.currentTimeline == Timeline.EMPTY) emptyList()
        else (0 until p.mediaItemCount).map { MediaItems.trackOf(p.getMediaItemAt(it)) }
        _state.value = PlayerUiState(
            current = p.currentMediaItem?.let(MediaItems::trackOf),
            isPlaying = p.isPlaying,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            positionMs = p.currentPosition,
            durationMs = p.duration.takeIf { it > 0 } ?: ((p.currentMediaItem?.mediaMetadata?.durationMs) ?: 0),
            queue = queue,
            currentIndex = p.currentMediaItemIndex,
            shuffle = p.shuffleModeEnabled,
            repeatMode = p.repeatMode,
            hasNext = p.hasNextMediaItem(),
            hasPrevious = p.hasPreviousMediaItem(),
        )
        if (p.isPlaying) startTicker(p) else ticker?.cancel()
    }

    /** Keeps the progress bar moving while playing. */
    private fun startTicker(p: Player) {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                _state.value = _state.value.copy(positionMs = p.currentPosition)
                delay(500)
            }
        }
    }
}
