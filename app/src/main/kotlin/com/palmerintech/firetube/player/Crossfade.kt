package com.palmerintech.firetube.player

/**
 * Fades between consecutive songs: the volume goes down over the end of a song and back up over
 * the start of the next. It's one player, so songs never overlap — this only shapes the volume
 * (the player's own volume, which is applied after [VolumeLeveler] measures, so leveling isn't
 * fooled), and gapless playback and the timeline are untouched.
 *
 * The setting is the whole transition: half of it fading out, half fading in. It only fades when
 * a song runs into the next on its own:
 * - skipping, picking a song or seeking never fades (the volume jumps back to full), and seeking
 *   into the fade-out plays the rest of that song at full volume,
 * - a SponsorBlock segment that runs to the end (an outro) counts as the end of the music, so the
 *   fade finishes before it and its skip ([onSegmentSkip]) doesn't count as a seek,
 * - short songs (under [MIN_TRACK_MS], or not much longer than the fades) play as they are.
 *
 * Pure state + arithmetic, driven by [PlaybackService]: positions are media time, so fades are
 * stretched by the playback speed to last the same in real time.
 */
class Crossfade {

    /** Whole transition length from settings, in ms (0 = off). */
    var lengthMs: Long = 0
        set(value) {
            field = value.coerceIn(0, MAX_SECONDS * 1000L)
            if (field == 0L) fadeInFrom = NONE
        }

    /** Where the current song's fade-in started (media ms), or [NONE]. */
    private var fadeInFrom = NONE

    /** The user sought into this song's fade-out: no fade-out (and so no fade-in after it). */
    private var fadeOutBlocked = false

    /** The last [volume] was inside the fade-out, i.e. the song is ending faded out. */
    private var fadingOut = false

    /** The next seek is SponsorBlock skipping a segment, not the user. */
    private var segmentSkipPending = false

    private var segments: List<LongRange> = emptyList()

    /** A new song: [automatic] when the previous one simply ended (not a skip, pick or error). */
    fun onItemTransition(automatic: Boolean) {
        fadeInFrom = if (automatic && fadingOut && lengthMs > 0) 0 else NONE
        fadingOut = false
        fadeOutBlocked = false
        segmentSkipPending = false
        segments = emptyList()
    }

    /** SponsorBlock segments for the current song (ms ranges). */
    fun setSegments(value: List<LongRange>) {
        segments = value
    }

    /** Call right before SponsorBlock seeks past a segment. */
    fun onSegmentSkip() {
        segmentSkipPending = true
    }

    /** The position jumped to [positionMs] by a seek (the user's, or SponsorBlock's — see [onSegmentSkip]). */
    fun onSeek(positionMs: Long, durationMs: Long, speed: Float) {
        if (segmentSkipPending) {
            segmentSkipPending = false
            // An intro skipped while fading in: carry on fading in from the new spot.
            if (fadeInFrom != NONE) fadeInFrom = positionMs
            return
        }
        fadeInFrom = NONE
        fadeOutBlocked = durationMs > 0 && positionMs >= musicEndMs(durationMs) - halfMs(speed)
    }

    /**
     * The volume (0..1) to play at. [canFadeOut] is false when nothing follows on its own (end of the
     * queue, repeat-one, pausing at the end of the song); [durationMs] is <= 0 while unknown.
     */
    fun volume(positionMs: Long, durationMs: Long, speed: Float, canFadeOut: Boolean): Float {
        val half = halfMs(speed)
        val known = durationMs > 0
        if (half <= 0 || (known && isShort(durationMs, half))) {
            fadingOut = false
            fadeInFrom = NONE // a short song after a fade-out: just play it
            return 1f
        }
        var v = 1f
        // Fading in still works while the length is unknown (the song may not be loaded yet).
        if (fadeInFrom != NONE) {
            val done = positionMs - fadeInFrom
            if (done >= half) fadeInFrom = NONE else v = ramp(done.toFloat() / half)
        }
        val left = if (known) musicEndMs(durationMs) - positionMs else Long.MAX_VALUE
        fadingOut = canFadeOut && !fadeOutBlocked && left < half
        if (fadingOut) v = minOf(v, ramp(left.toFloat() / half))
        return v
    }

    /** Whether [volume] may change soon, so it should be polled often. */
    fun active(positionMs: Long, durationMs: Long, speed: Float): Boolean {
        val half = halfMs(speed)
        if (half <= 0) return false
        return fadeInFrom != NONE || (durationMs > 0 && musicEndMs(durationMs) - positionMs < half + POLL_AHEAD_MS)
    }

    /** End of the music: the start of a SponsorBlock segment that runs to the end, else the end. */
    internal fun musicEndMs(durationMs: Long): Long =
        segments.filter { it.last >= durationMs - TRAILING_SEGMENT_SLACK_MS && it.first < durationMs }
            .minOfOrNull { it.first } ?: durationMs

    /** One side of the transition, in media ms. */
    private fun halfMs(speed: Float): Long = (lengthMs / 2 * speed.coerceAtLeast(0.1f)).toLong()

    private fun isShort(durationMs: Long, half: Long) = durationMs < MIN_TRACK_MS || durationMs < half * 4

    companion object {
        const val MAX_SECONDS = 12
        const val MIN_TRACK_MS = 30_000L

        /** SponsorBlock segment ends are rounded; one ending this close to the end counts as an outro. */
        const val TRAILING_SEGMENT_SLACK_MS = 2_000L
        const val POLL_AHEAD_MS = 1_000L
        private const val NONE = -1L

        /** Fade shape for progress 0..1: squared, so the change in loudness sounds even (not a sudden drop at the end). */
        fun ramp(t: Float): Float = t.coerceIn(0f, 1f).let { it * it }
    }
}

/** Playback speed bounds and the choices offered in Now Playing. Pitch is always kept. */
object PlaybackSpeed {
    const val MIN = 0.5f
    const val MAX = 2f
    val choices = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    /** Into range, rounded to 0.05 (the slider's step), so stored values stay tidy. */
    fun clamp(speed: Float): Float =
        if (speed.isNaN()) 1f else (Math.round(speed.coerceIn(MIN, MAX) * 20) / 20f)

    fun label(speed: Float): String = "${clamp(speed).toString().removeSuffix(".0")}x"
}
