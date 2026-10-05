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
 * trip answered with 1x, forever. So it gives up after [MAX_REJECTIONS] for that player and
 * reports [unsupported]; [reset] (new setting, other device, media loaded) tries again.
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

    /** Whether [setting] has been sent to this player since the last [reset]. */
    private var sent = false
    private var rejections = 0

    fun reset(setting: Float = this.setting) {
        this.setting = PlaybackSpeed.clamp(setting)
        sent = false
        rejections = 0
        _unsupported.value = false
    }

    /**
     * The speed to send the player now that it reports [reported], or null to leave it be.
     * [canSet] is false when the player doesn't offer speed changes at all.
     */
    fun next(reported: Float, canSet: Boolean): Float? {
        if (PlaybackSpeed.same(reported, setting) || _unsupported.value) return null
        if (!canSet) {
            _unsupported.value = true
            return null
        }
        if (sent && ++rejections >= MAX_REJECTIONS) {
            _unsupported.value = true
            return null
        }
        sent = true
        return setting
    }

    companion object {
        const val MAX_REJECTIONS = 2
    }
}
