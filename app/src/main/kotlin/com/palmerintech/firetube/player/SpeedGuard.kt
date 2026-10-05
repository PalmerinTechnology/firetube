package com.palmerintech.firetube.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the active player at the saved playback speed without fighting one that won't take it.
 *
 * The saved setting is the source of truth (a Cast hand-off copies the other player's speed
 * across), so [PlaybackService] puts it back whenever the player reports something else. But some
 * Cast receivers and speaker groups never accept a speed: each attempt would be a network round
 * trip answered with 1x, forever. So it gives up after [MAX_REJECTIONS] refusals in a row, or
 * once it has sent [MAX_SENDS] times (a player that takes the speed and then drops it again), and
 * reports [unsupported]. [reset] — a new setting, another device, media loaded, each new song, a
 * new service — tries again, so that's at most [MAX_SENDS] sends per song.
 *
 * Shared through the app container so Now Playing can show the saved speed rather than every
 * value the player passes through — or the player's own once this gives up. Main thread only.
 */
class SpeedGuard {
    private val _unsupported = MutableStateFlow(false)

    /** The active player won't take the saved speed (it plays at its own). */
    val unsupported: StateFlow<Boolean> = _unsupported.asStateFlow()

    /** The saved speed being applied. */
    var setting: Float = 1f
        private set

    /** [setting] was sent and the player hasn't reported it yet. */
    private var awaiting = false

    /** Refusals in a row (cleared once the player takes the speed). */
    private var rejections = 0
    private var sends = 0

    fun reset(setting: Float = this.setting) {
        this.setting = PlaybackSpeed.clamp(setting)
        awaiting = false
        rejections = 0
        sends = 0
        _unsupported.value = false
    }

    /**
     * The speed to send the player now that it reports [reported], or null to leave it be.
     * [canSet] is false when the player doesn't offer speed changes at all.
     */
    fun next(reported: Float, canSet: Boolean): Float? {
        if (_unsupported.value) return null
        if (PlaybackSpeed.same(reported, setting)) {
            // Taken: a later drift (another controller, a receiver resetting) is a fresh start.
            awaiting = false
            rejections = 0
            return null
        }
        val refused = awaiting && ++rejections >= MAX_REJECTIONS
        if (!canSet || refused || sends >= MAX_SENDS) {
            _unsupported.value = true
            return null
        }
        awaiting = true
        sends++
        return setting
    }

    companion object {
        const val MAX_REJECTIONS = 2
        const val MAX_SENDS = 2
    }
}
