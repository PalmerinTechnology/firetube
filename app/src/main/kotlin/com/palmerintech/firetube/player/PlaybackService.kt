package com.palmerintech.firetube.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
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
 * recovery, queue restore and the audio settings (equalizer, crossfade, speed).
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

    private val leveler = VolumeLeveler()
    private val effects = AudioEffects(leveler)
    private val crossfade = Crossfade()

    /** The saved playback speed: the source of truth for whichever player is active (phone or Chromecast). */
    private var speedSetting = 1f
    private var crossfadeJob: Job? = null
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
        // Effects and fades are the phone's own audio, so they follow the ExoPlayer directly.
        exoPlayer.addListener(audioListener)
        effects.attach(exoPlayer.audioSessionId)

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback(container, scope))
            .setSessionActivity(openApp)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) },
        )

        scope.launch { container.sleepTimer.fired.collect { player.pause() } }
        scope.launch {
            container.settings.settings.collect {
                leveler.enabled = it.volumeLeveling
                effects.update(it.equalizerPreset, it.bassBoost)
                crossfade.lengthMs = it.crossfadeSeconds * 1000L
                applyCrossfade()
                speedSetting = PlaybackSpeed.clamp(it.playbackSpeed)
                applySpeed()
            }
        }
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
        session?.release()
        session = null
        effects.release()
        player.release() // a CastPlayer releases the ExoPlayer it wraps
        container.castServer.stop()
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
            // Cast hand-off copies the other player's speed across; put the saved one back.
            applySpeed()
            // Back on the phone: the cast proxy (and its wake/Wi-Fi locks) is no longer needed.
            when (deviceInfo.playbackType) {
                androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_LOCAL -> container.castServer.stop()
                // Casting a paused queue shouldn't hold the wake/Wi-Fi locks until the next play.
                else -> container.castServer.setStreaming(player.isPlaying)
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            if (!PlaybackSpeed.same(playbackParameters.speed, speedSetting)) applySpeed()
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

    /** The phone's player only: crossfade volume and the audio session for effects. */
    private val audioListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) = effects.attach(audioSessionId)

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // REPEAT: a one-song queue on repeat-all looping (repeat-one never fades out).
            crossfade.onItemTransition(
                automatic = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
            )
            applyCrossfade()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK || oldPosition.mediaItemIndex != newPosition.mediaItemIndex) return
            crossfade.onSeek(oldPosition.positionMs, newPosition.positionMs, exoPlayer.duration, exoPlayer.playbackParameters.speed)
            applyCrossfade()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = applyCrossfade()
    }

    /**
     * Sets the phone player's volume from [crossfade], and keeps doing so while playing: often
     * around a fade, rarely otherwise. Only the ExoPlayer's volume — never a Chromecast's.
     */
    private fun applyCrossfade() {
        crossfadeJob?.cancel()
        if (crossfade.lengthMs == 0L) {
            exoPlayer.volume = 1f
            return
        }
        fun update(): Boolean {
            val position = exoPlayer.currentPosition
            val duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: 0
            val speed = exoPlayer.playbackParameters.speed
            val canFadeOut = exoPlayer.hasNextMediaItem() && exoPlayer.repeatMode != Player.REPEAT_MODE_ONE &&
                !exoPlayer.pauseAtEndOfMediaItems
            exoPlayer.volume = crossfade.volume(position, duration, speed, canFadeOut)
            return crossfade.active(position, duration, speed)
        }
        update()
        if (!exoPlayer.isPlaying) return
        crossfadeJob = scope.launch {
            while (isActive) {
                delay(if (update()) FADE_TICK_MS else IDLE_TICK_MS)
            }
        }
    }

    /** Puts the saved speed on the active player (pitch kept). A player that can't take it is left as it is. */
    private fun applySpeed() {
        if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return
        if (!PlaybackSpeed.same(player.playbackParameters.speed, speedSetting)) player.setPlaybackSpeed(speedSetting)
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
            if (segments.isEmpty() || player.currentMediaItem?.mediaId != videoId) return@launch
            crossfade.setSegments(segments)
            while (isActive && player.currentMediaItem?.mediaId == videoId) {
                val pos = player.currentPosition
                segments.firstOrNull { pos in it && it.last - pos > 1000 }?.let {
                    crossfade.onSegmentSkip()
                    player.seekTo(it.last)
                }
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
        if (saved.tracks.isEmpty() || player.mediaItemCount > 0) return
        player.setMediaItems(
            saved.tracks.map { MediaItems.of(it.toTrack()) },
            saved.index.coerceIn(0, saved.tracks.lastIndex),
            saved.positionMs,
        )
        player.prepare()
    }

    private companion object {
        const val MAX_CONSECUTIVE_FAILURES = 3
        const val FADE_TICK_MS = 50L
        const val IDLE_TICK_MS = 500L
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? =
    generateSequence(this) { it.cause }.take(10).filterIsInstance<T>().firstOrNull()

private fun PlaybackException.isOffline(): Boolean =
    errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
        generateSequence<Throwable>(this) { it.cause }.take(10)
            .any { it is UnknownHostException || it is ConnectException || it is SocketTimeoutException }
