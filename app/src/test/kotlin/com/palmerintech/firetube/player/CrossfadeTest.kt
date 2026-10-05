package com.palmerintech.firetube.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeTest {
    private val song = 200_000L // 3:20

    /** 6 s setting: 3 s out, 3 s in. */
    private fun crossfade() = Crossfade().apply { lengthMs = 6_000 }

    private fun Crossfade.at(positionMs: Long, durationMs: Long = song, speed: Float = 1f, canFadeOut: Boolean = true) =
        volume(positionMs, durationMs, speed, canFadeOut)

    /** Plays the end of a song into the next one on its own. */
    private fun Crossfade.endNaturally(durationMs: Long = song) {
        at(durationMs - 50, durationMs)
        onItemTransition(automatic = true)
    }

    @Test
    fun offNeverChangesTheVolume() {
        val c = Crossfade()
        listOf(0L, 1_000L, song - 2_000, song - 10).forEach { assertEquals(1f, c.at(it), 0f) }
        c.endNaturally()
        assertEquals(1f, c.at(0), 0f)
    }

    @Test
    fun fadeOutStartsHalfTheLengthBeforeTheEnd() {
        val c = crossfade()
        assertEquals(1f, c.at(song - 3_001), 0f)
        assertTrue(c.at(song - 2_999) < 1f)
        assertEquals(0.25f, c.at(song - 1_500), 0.001f) // halfway: squared ramp
        assertEquals(0f, c.at(song), 0.001f)
    }

    @Test
    fun rampIsSmoothAndMonotonic() {
        assertEquals(0f, Crossfade.ramp(0f), 0f)
        assertEquals(1f, Crossfade.ramp(1f), 0f)
        assertEquals(0f, Crossfade.ramp(-1f), 0f)
        assertEquals(1f, Crossfade.ramp(2f), 0f)
        val steps = (0..100).map { Crossfade.ramp(it / 100f) }
        assertTrue(steps.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun nextSongFadesInAfterANaturalEnd() {
        val c = crossfade()
        c.endNaturally()
        assertEquals(0f, c.at(0), 0.001f)
        assertEquals(0.25f, c.at(1_500), 0.001f)
        assertEquals(1f, c.at(3_000), 0f)
        assertEquals(1f, c.at(10_000), 0f)
    }

    @Test
    fun fadeInWorksBeforeTheLengthIsKnown() {
        val c = crossfade()
        c.endNaturally()
        assertEquals(0f, c.at(0, durationMs = 0), 0.001f)
    }

    @Test
    fun skippingNeverFades() {
        val c = crossfade()
        assertTrue(c.at(song - 1_000) < 1f)
        c.onItemTransition(automatic = false) // pressed next mid-fade
        assertEquals(1f, c.at(0), 0f)
    }

    @Test
    fun noFadeOutWhenNothingFollowsOnItsOwn() {
        val c = crossfade()
        assertEquals(1f, c.at(song - 1_000, canFadeOut = false), 0f)
        c.onItemTransition(automatic = true)
        assertEquals(1f, c.at(0), 0f)
    }

    @Test
    fun seekingIntoTheFadeOutPlaysItAtFullVolume() {
        val c = crossfade()
        c.onSeek(60_000, song - 2_000, song, 1f)
        assertEquals(1f, c.at(song - 2_000), 0f)
        assertEquals(1f, c.at(song - 100), 0f)
        c.onItemTransition(automatic = true)
        assertEquals(1f, c.at(0), 0f) // and no fade-in after it
    }

    @Test
    fun seekingBackOutOfTheFadeOutRearmsIt() {
        val c = crossfade()
        c.onSeek(60_000, song - 2_000, song, 1f)
        c.onSeek(song - 2_000, 60_000, song, 1f)
        assertTrue(c.at(song - 1_000) < 1f)
    }

    @Test
    fun seekingDuringTheFadeInJumpsToFullVolume() {
        val c = crossfade()
        c.endNaturally()
        c.onSeek(500, 1_000, song, 1f)
        assertEquals(1f, c.at(1_000), 0f)
    }

    @Test
    fun sponsorBlockIntroSkipKeepsFadingIn() {
        val c = crossfade()
        c.endNaturally()
        c.at(500)
        c.onSegmentSkip()
        c.onSeek(500, 15_000, song, 1f)
        // Same volume as just before the skip (not back to silence), then on up.
        assertEquals(Crossfade.ramp(500 / 3_000f), c.at(15_000), 0.001f)
        assertEquals(0.25f, c.at(16_000), 0.001f)
        assertEquals(1f, c.at(17_500), 0f)
    }

    @Test
    fun sponsorBlockOutroIsTheEndOfTheMusic() {
        val c = crossfade()
        c.setSegments(listOf(20_000L..30_000L, 185_000L..199_500L))
        assertEquals(185_000L, c.musicEndMs(song))
        assertEquals(1f, c.at(181_000), 0f)
        assertEquals(0.25f, c.at(183_500), 0.001f)
        assertEquals(0f, c.at(185_200), 0.001f)
        // The outro's skip isn't the user's seek: the next song still fades in.
        c.onSegmentSkip()
        c.onSeek(185_500, 199_500, song, 1f)
        assertEquals(0f, c.at(199_500), 0.001f)
        c.onItemTransition(automatic = true)
        assertEquals(0f, c.at(0), 0.001f)
    }

    @Test
    fun aSegmentInTheMiddleIsNotAnOutro() {
        val c = crossfade()
        c.setSegments(listOf(100_000L..120_000L))
        assertEquals(song, c.musicEndMs(song))
    }

    @Test
    fun aLoopingOneSongQueueFadesBackIn() {
        // Repeat-all with one song: the service reports the loop as automatic too.
        val c = crossfade()
        assertEquals(0f, c.at(song), 0.001f)
        c.onItemTransition(automatic = true)
        assertEquals(0f, c.at(0), 0.001f)
        assertEquals(1f, c.at(3_000), 0f)
    }

    @Test
    fun shortSongsAreLeftAlone() {
        val c = crossfade()
        assertEquals(1f, c.at(19_000, durationMs = 20_000), 0f)
        // Long enough for the minimum, but not much longer than a 12 s transition.
        val long = Crossfade().apply { lengthMs = 12_000 }
        assertEquals(1f, long.at(22_000, durationMs = 23_000), 0f)
        // A short song after a fade-out starts at full volume.
        c.endNaturally()
        assertEquals(1f, c.at(0, durationMs = 20_000), 0f)
    }

    @Test
    fun fadesLastTheSameRealTimeAtOtherSpeeds() {
        val c = crossfade()
        // At 2x, 3 s of real time is 6 s of song.
        assertEquals(0.25f, c.at(song - 3_000, speed = 2f), 0.001f)
        assertEquals(1f, c.at(song - 6_001, speed = 2f), 0f)
    }

    @Test
    fun pollsOftenOnlyAroundAFade() {
        val c = crossfade()
        assertFalse(c.active(60_000, song, 1f))
        assertTrue(c.active(song - 3_500, song, 1f))
        c.endNaturally()
        assertTrue(c.active(0, song, 1f))
        c.at(4_000)
        assertFalse(c.active(4_000, song, 1f))
        assertFalse(Crossfade().active(song - 100, song, 1f))
    }

    @Test
    fun lengthIsCapped() {
        assertEquals(12_000L, Crossfade().apply { lengthMs = 60_000 }.lengthMs)
        assertEquals(0L, Crossfade().apply { lengthMs = -5 }.lengthMs)
    }
}
