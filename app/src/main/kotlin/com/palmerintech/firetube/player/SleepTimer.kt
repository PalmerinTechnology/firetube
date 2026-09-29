package com.palmerintech.firetube.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Pauses playback after a delay ([fired]), or at the end of the current song ([State.EndOfTrack],
 * which the service maps to ExoPlayer's pause-at-end-of-item).
 */
class SleepTimer(private val scope: CoroutineScope) {
    sealed interface State {
        data object Off : State
        data class At(val epochMillis: Long) : State
        data object EndOfTrack : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state

    private val _fired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val fired: SharedFlow<Unit> = _fired

    private var job: Job? = null

    fun start(minutes: Int) {
        job?.cancel()
        val end = System.currentTimeMillis() + minutes * 60_000L
        _state.value = State.At(end)
        job = scope.launch {
            delay(minutes * 60_000L)
            fire()
        }
    }

    fun endOfTrack() {
        job?.cancel()
        _state.value = State.EndOfTrack
    }

    fun cancel() {
        job?.cancel()
        _state.value = State.Off
    }

    private fun fire() {
        _state.value = State.Off
        _fired.tryEmit(Unit)
    }
}
