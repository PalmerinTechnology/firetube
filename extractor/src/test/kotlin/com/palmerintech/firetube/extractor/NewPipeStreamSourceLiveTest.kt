package com.palmerintech.firetube.extractor

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Talks to real YouTube. Excluded from normal runs; `./gradlew :extractor:test -Plive`.
 * CI runs this nightly so we learn about YouTube-side breakage before users do.
 */
class NewPipeStreamSourceLiveTest {
    private val source = NewPipeStreamSource()

    // Long-lived, official uploads that should stay up.
    private val knownTracks = listOf(
        "dQw4w9WgXcQ", // Rick Astley - Never Gonna Give You Up
        "fJ9rUzIMcZQ", // Queen - Bohemian Rhapsody
        "kJQP7kiw5Fk", // Luis Fonsi - Despacito
    )

    @Test
    fun searchFindsSongs() = runBlocking {
        val page = source.search("bohemian rhapsody queen", SearchFilter.SONGS)
        val tracks = page.items.filterIsInstance<SearchResult.TrackResult>()
        println("search -> ${tracks.take(3).map { it.track.title + " / " + it.track.artist }}")
        assertTrue("expected song results", tracks.isNotEmpty())
    }

    @Test
    fun suggestionsWork() = runBlocking {
        val s = source.suggestions("never gonna")
        println("suggestions -> $s")
        assertTrue(s.isNotEmpty())
    }

    @Test
    fun knownTracksResolveAndStreamBytes() = runBlocking {
        val http = OkHttpClient()
        for (id in knownTracks) {
            val stream = source.resolve(id)
            println("$id -> ${stream.mimeType} ${stream.bitrate}bps, related=${stream.related.size}")
            assertEquals(id, stream.trackId)
            // Resolving isn't enough — YouTube can hand out URLs that 403. Fetch real audio bytes.
            val req = Request.Builder().url(stream.url).header("Range", "bytes=0-65535").build()
            http.newCall(req).execute().use { resp ->
                println("$id bytes -> HTTP ${resp.code}")
                assertTrue("HTTP ${resp.code} for $id", resp.code == 200 || resp.code == 206)
                assertTrue(resp.body.bytes().size > 1000)
            }
        }
    }

    @Test
    fun playlistLoads() = runBlocking {
        // YouTube's own "Top 100 Songs Global" chart playlist.
        val (summary, page) = source.playlist(NewPipeStreamSource.TOP_SONGS_GLOBAL)
        println("playlist -> ${summary.title} (${page.items.size} items)")
        assertTrue(page.items.isNotEmpty())
    }

    @Test
    fun trendingMusic() = runBlocking {
        val tracks = source.trending()
        println("trending -> ${tracks.size}: ${tracks.take(3).map { it.title }}")
        assertTrue(tracks.isNotEmpty())
    }
}
