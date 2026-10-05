package com.palmerintech.firetube.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.palmerintech.firetube.FireTubeApp
import com.palmerintech.firetube.R
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.player.cast.CastItemConverter
import com.palmerintech.firetube.ui.MainActivity
import com.palmerintech.firetube.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Owns the player. Media3 gives us the notification, lock screen, Bluetooth/headset buttons,
 * audio focus, Android Auto and Chromecast hand-off from the session; this class adds FireTube's
 * behaviour on top: history, autoplay radio, SponsorBlock skipping, the sleep timer, error
 * recovery, queue restore and the home-screen widget.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container by lazy { (application as FireTubeApp).container }

    /** The phone's own player. */
    private lateinit var exoPlayer: ExoPlayer

    /** What the session controls: [exoPlayer], or a [CastPlayer] wrapping it that moves playback to a Chromecast. */
    private lateinit var player: Player
    private var session: MediaLibrarySession? = null
    private val library by lazy { LibraryCallback(container, scope) }
    private lateinit var widget: WidgetUpdater

    private val leveler = VolumeLeveler()
    private var skipJob: Job? = null
    private var extendJob: Job? = null

    /** Retries of the current item after a (probably expired-URL) error. */
    private var retriesForItem = 0

    /** Items skipped in a row because they failed; reset once something plays or the user picks something. */
    private var consecutiveFailures = 0

    /** Set while onPlayerError itself skips ahead, so that seek doesn't count as the user's. */
    private var skippingAfterError = false

    /** Whether the current item's play has been written to history (once per play). */
    private var recorded = false

    /** Listened time per song, for Your stats. */
    private lateinit var listens: ListenTracker

    override fun onCreate() {
        super.onCreate()
        // Volume leveling runs inside the audio pipeline, between the decoder and the speaker.
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(leveler))
                    // Float output deliberately stays off: DefaultAudioSink then converts to 16-bit PCM
                    // before custom processors, which VolumeLeveler requires.
                    .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                    .build()
        }
        exoPlayer = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(container.mediaStack.playbackFactory))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player = if (container.castAvailable) {
            CastPlayer.Builder(this)
                .setLocalPlayer(exoPlayer)
                .setRemotePlayer(RemoteCastPlayer.Builder(this).setMediaItemConverter(CastItemConverter(container.castServer)).build())
                .build()
        } else {
            exoPlayer
        }
        player.addListener(listener)
        widget = WidgetUpdater(this, player, scope).also { it.start() }

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, library)
            .setSessionActivity(openApp)
            .build()
        listens = ListenTracker(player, container.library).also(player::addListener)

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) },
        )

        scope.launch { container.sleepTimer.fired.collect { player.pause() } }
        scope.launch { container.settings.settings.collect { leveler.enabled = it.volumeLeveling } }
        // "End of song": let the phone's player pause itself exactly at the end of the current item.
        scope.launch {
            container.sleepTimer.state.collect { exoPlayer.pauseAtEndOfMediaItems = it == SleepTimer.State.EndOfTrack }
        }
        scope.launch { restoreQueue() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while paused shouldn't leave a zombie notification.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // Synchronous: the scope is about to be cancelled.
        snapshotQueue()?.let { (tracks, index, position) -> container.queueStore.saveNow(tracks, index, position) }
        widget.stop()
        session?.release()
        session = null
        player.release() // a CastPlayer releases the ExoPlayer it wraps
        container.castServer.stop()
        listens.flush()
        scope.cancel()
        super.onDestroy()
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            retriesForItem = 0
            recorded = false
            val userChoice = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
            if (userChoice && !skippingAfterError) consecutiveFailures = 0
            skippingAfterError = false
            // "End of song" on a Chromecast: ExoPlayer's pause-at-end only applies on the phone.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && isCasting() &&
                container.sleepTimer.state.value == SleepTimer.State.EndOfTrack
            ) {
                player.pause()
                container.sleepTimer.cancel()
            }
            mediaItem ?: return
            // Gapless transitions keep isPlaying true, so onIsPlayingChanged won't fire for this item.
            if (player.isPlaying) recordCurrent()
            startSponsorSkipping(mediaItem.mediaId)
            maybeExtendQueue()
            saveQueue()
        }

        override fun onDeviceInfoChanged(deviceInfo: androidx.media3.common.DeviceInfo) {
            // Back on the phone: the cast proxy (and its wake/Wi-Fi locks) is no longer needed.
            when (deviceInfo.playbackType) {
                androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_LOCAL -> container.castServer.stop()
                // Casting a paused queue shouldn't hold the wake/Wi-Fi locks until the next play.
                else -> container.castServer.setStreaming(player.isPlaying)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) maybeExtendQueue()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) container.sleepTimer.cancel()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isCasting()) container.castServer.setStreaming(isPlaying)
            if (!isPlaying) {
                saveQueue()
                return
            }
            consecutiveFailures = 0
            recordCurrent()
        }

        override fun onPlayerError(error: PlaybackException) {
            val item = player.currentMediaItem ?: return
            Timber.w(error, "Playback error on %s", item.mediaId)
            if (error.isOffline()) {
                // No point burning through the queue; stay put and let the user press play again.
                return
            }
            val unavailable = error.findCause<StreamUnavailableException>()
            if (unavailable?.permanent != true && retriesForItem < 1) {
                // Usually an expired stream URL after a long pause: resolve again and carry on.
                retriesForItem++
                container.resolver.invalidate(item.mediaId)
                player.prepare()
                return
            }
            // Unplayable (removed, blocked, or the extractor is broken): skip, but not forever.
            consecutiveFailures++
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES || !player.hasNextMediaItem()) return
            skippingAfterError = true
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        }
    }

    private fun isCasting() = player.deviceInfo.playbackType == androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE

    /** Writes the current item to history, once per play, only when audio is actually playing. */
    private fun recordCurrent() {
        if (recorded) return
        val item = player.currentMediaItem ?: return
        recorded = true
        val track = MediaItems.trackOf(item)
        scope.launch { container.library.recordPlay(track) }
    }

    /**
     * Autoplay: when we're about to run out of songs, append related ones. Skipped while shuffling
     * — appended items would be shuffled in anywhere, even before the current song.
     */
    private fun maybeExtendQueue() {
        if (player.repeatMode != Player.REPEAT_MODE_OFF || player.shuffleModeEnabled) return
        val current = player.currentMediaItem ?: return
        if (itemsLeftAfterCurrent() >= 2) return
        extendJob?.cancel()
        extendJob = scope.launch {
            if (!container.settings.current().autoplay) return@launch
            val related = container.resolver.cachedRelated(current.mediaId)
                ?: runCatching { container.resolver.resolve(current.mediaId).related }.getOrDefault(emptyList())
            // The user may have started something else while we were fetching.
            if (player.currentMediaItem?.mediaId != current.mediaId || itemsLeftAfterCurrent() >= 2) return@launch
            val queued = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            val recent = container.library.recentOnce(50).map { it.id }.toSet()
            val additions = related.filter { it.id !in queued && it.id !in recent && it.durationSeconds in 60..900 }
                .ifEmpty { related.filter { it.id !in queued } }
                .take(10)
            if (additions.isEmpty()) return@launch
            val ended = player.playbackState == Player.STATE_ENDED
            player.addMediaItems(additions.map(MediaItems::of))
            if (ended) {
                player.seekToNextMediaItem()
                player.play()
            }
        }
    }

    /** How many items will play after the current one (up to 2). */
    private fun itemsLeftAfterCurrent(): Int {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return 0
        var index = player.currentMediaItemIndex
        var count = 0
        while (count < 2) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (index == C.INDEX_UNSET) break
            count++
        }
        return count
    }

    private fun startSponsorSkipping(videoId: String) {
        skipJob?.cancel()
        skipJob = scope.launch {
            if (!container.settings.current().sponsorBlock) return@launch
            val segments = container.sponsorBlock.segments(videoId)
            if (segments.isEmpty()) return@launch
            while (isActive && player.currentMediaItem?.mediaId == videoId) {
                val pos = player.currentPosition
                segments.firstOrNull { pos in it && it.last - pos > 1000 }?.let { player.seekTo(it.last) }
                delay(500)
            }
        }
    }

    private fun snapshotQueue(): Triple<List<Track>, Int, Long>? {
        if (player.mediaItemCount == 0) return null
        val tracks = (0 until player.mediaItemCount).map { MediaItems.trackOf(player.getMediaItemAt(it)) }
        return Triple(tracks, player.currentMediaItemIndex, player.currentPosition)
    }

    private fun saveQueue() {
        val (tracks, index, position) = snapshotQueue() ?: return
        container.queueStore.save(tracks, index, position)
    }

    private suspend fun restoreQueue() {
        if (player.mediaItemCount > 0) return
        val saved = container.queueStore.load() ?: return
        // Play may have been pressed meanwhile (e.g. on the widget); the session resumes the queue then.
        if (saved.tracks.isEmpty() || player.mediaItemCount > 0 || library.resuming) return
        player.setMediaItems(
            saved.tracks.map { MediaItems.of(it.toTrack()) },
            saved.index.coerceIn(0, saved.tracks.lastIndex),
            saved.positionMs,
        )
        player.prepare()
    }

    private companion object {
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? =
    generateSequence(this) { it.cause }.take(10).filterIsInstance<T>().firstOrNull()

private fun PlaybackException.isOffline(): Boolean =
    errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
        generateSequence<Throwable>(this) { it.cause }.take(10)
            .any { it is UnknownHostException || it is ConnectException || it is SocketTimeoutException }
