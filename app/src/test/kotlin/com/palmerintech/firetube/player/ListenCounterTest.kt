package com.palmerintech.firetube.player

import com.palmerintech.firetube.extractor.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenCounterTest {
    private var elapsed = 1_000L
    private var wall = 1_700_000_000_000L
    private val counter = ListenCounter({ elapsed }, { wall })

    private fun track(id: String, seconds: Long = 200) = Track(id, "Song $id", "Artist", seconds, null)

    /** Lets time pass on both clocks. */
    private fun pass(ms: Long) {
        elapsed += ms
        wall += ms
    }

    @Test
    fun countsOnlyTimeSpentPlaying() {
        counter.onItem(track("a"), isPlaying = false)
        pass(60_000) // queued but not started (e.g. a restored queue)
        assertNull(counter.current())
        counter.onPlaying(true)
        val startedAt = wall
        pass(20_000)
        // Paused at 20s: below the 30s threshold, so nothing to save yet.
        assertNull(counter.onPlaying(false))
        pass(10 * 60_000) // paused
        counter.onPlaying(true)
        pass(15_000)
        val saved = counter.onPlaying(false)!!
        assertEquals(35_000L, saved.msListened)
        assertEquals("the listen starts when it first made sound", startedAt, saved.startedAt)
    }

    @Test
    fun bufferingAndSkippedSegmentsDontCount() {
        // SponsorBlock skips are seeks: they don't stop the clock, but the skipped audio never plays,
        // so it's never added. Buffering after the seek reports isPlaying = false.
        counter.onItem(track("a"), isPlaying = true)
        pass(40_000)
        counter.onPlaying(false) // buffering after the jump
        pass(5_000)
        counter.onPlaying(true)
        pass(10_000)
        assertEquals(50_000L, counter.current()!!.msListened)
    }

    @Test
    fun autoplayTransitionClosesOneListenAndOpensTheNext() {
        counter.onItem(track("a"), isPlaying = true)
        pass(180_000)
        // Gapless: isPlaying stays true across the transition.
        val done = counter.onItem(track("b"), isPlaying = true)!!
        assertEquals("a", done.track.id)
        assertEquals(180_000L, done.msListened)
        pass(31_000)
        val next = counter.current()!!
        assertEquals("b", next.track.id)
        assertEquals(31_000L, next.msListened)
    }

    @Test
    fun castHandOffOfTheSameSongIsOneListen() {
        val t = track("a")
        counter.onItem(t, isPlaying = true)
        val startedAt = wall
        pass(40_000)
        // Moving to a Chromecast replaces the queue with the same song, carrying on where it was;
        // it may pause meanwhile.
        assertEquals(40_000L, counter.onItem(t, isPlaying = false, queueReplacedAtMs = 40_500)!!.msListened)
        pass(3_000)
        counter.onPlaying(true)
        pass(20_000)
        val listen = counter.onItem(track("b"), isPlaying = true)!!
        assertEquals(60_000L, listen.msListened)
        assertEquals(startedAt, listen.startedAt)
    }

    @Test
    fun newQueueWithADifferentSongStartsANewListen() {
        counter.onItem(track("a"), isPlaying = true)
        pass(40_000)
        assertEquals("a", counter.onItem(track("b"), isPlaying = true, queueReplacedAtMs = 40_000)!!.track.id)
        assertEquals("b", counter.current()!!.track.id)
    }

    @Test
    fun replayingTheSameSongFromAListIsANewPlay() {
        val t = track("a")
        counter.onItem(t, isPlaying = true)
        pass(90_000)
        // Tapped again in search / a playlist / Top songs: same id, but it starts from the top.
        val first = counter.onItem(t, isPlaying = true, queueReplacedAtMs = 0)!!
        assertEquals(90_000L, first.msListened)
        pass(40_000)
        val second = counter.onItem(null, isPlaying = false)!!
        assertEquals(40_000L, second.msListened)
        assertEquals(first.startedAt + 90_000, second.startedAt)
    }

    @Test
    fun replayingAFinishedSongFromAListIsANewPlay() {
        val t = track("a", seconds = 60)
        counter.onItem(t, isPlaying = true)
        pass(60_000)
        counter.onPlaying(false) // ended
        pass(5 * 60_000)
        assertEquals(60_000L, counter.onItem(t, isPlaying = true, queueReplacedAtMs = 0)!!.msListened)
        pass(1_000)
        assertEquals(1_000L, counter.current()!!.msListened)
    }

    @Test
    fun skippedSongIsNotAPlay() {
        counter.onItem(track("a"), isPlaying = true)
        pass(29_999)
        assertNull(counter.onItem(track("b"), isPlaying = true))
    }

    @Test
    fun repeatOneStartsANewPlay() {
        val t = track("a", seconds = 60)
        counter.onItem(t, isPlaying = true)
        pass(60_000)
        val first = counter.onItem(t, isPlaying = true)!!
        pass(45_000)
        val second = counter.onItem(null, isPlaying = false)!!
        assertEquals(60_000L, first.msListened)
        assertEquals(45_000L, second.msListened)
        assertEquals(first.startedAt + 60_000, second.startedAt)
    }

    @Test
    fun thresholdIsThirtySecondsOrHalfAShortSong() {
        assertEquals(30_000L, ListenCounter.threshold(200))
        assertEquals(30_000L, ListenCounter.threshold(60))
        assertEquals(10_000L, ListenCounter.threshold(20))
        assertEquals("unknown length", 30_000L, ListenCounter.threshold(0))

        counter.onItem(track("short", seconds = 20), isPlaying = true)
        pass(9_000)
        assertNull(counter.onPlaying(false))
        counter.onPlaying(true)
        pass(1_000)
        assertEquals(10_000L, counter.onPlaying(false)!!.msListened)
    }

    @Test
    fun nothingToCountWithoutAnItem() {
        assertNull(counter.onPlaying(true))
        pass(60_000)
        assertNull(counter.onPlaying(false))
        assertNull(counter.current())
    }
}
