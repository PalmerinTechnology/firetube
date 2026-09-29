package com.palmerintech.firetube.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline tests for the helpers; see [NewPipeStreamSourceLiveTest] for the real thing. */
class NewPipeStreamSourceTest {

    @Test
    fun videoIdFromCommonLinkShapes() {
        val id = "dQw4w9WgXcQ"
        listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://m.youtube.com/watch?v=$id&list=PL123&index=2",
            "https://music.youtube.com/watch?v=$id&feature=share",
            "https://youtu.be/$id?si=abc",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/embed/$id",
            "Check this out https://youtu.be/$id",
        ).forEach { assertEquals(it, id, NewPipeStreamSource.videoId(it)) }
    }

    @Test
    fun videoIdRejectsNonVideoLinks() {
        assertNull(NewPipeStreamSource.videoId("https://www.youtube.com/playlist?list=PL4fGSI1pDJn6puJdseH2Rt9sMvt9E2M4i"))
        assertNull(NewPipeStreamSource.videoId("https://example.com"))
    }

    @Test
    fun expiryComesFromTheUrlWithAMargin() {
        val expire = 1_900_000_000L
        val at = NewPipeStreamSource.expiryOf("https://rr1---sn.googlevideo.com/videoplayback?expire=$expire&itag=140")
        assertEquals(expire * 1000 - 10 * 60_000, at)
    }

    @Test
    fun expiryDefaultsToAboutAnHour() {
        val before = System.currentTimeMillis()
        val at = NewPipeStreamSource.expiryOf("https://example.com/audio.m4a")
        assertTrue(at in before + 59 * 60_000..before + 61 * 60_000)
    }
}
