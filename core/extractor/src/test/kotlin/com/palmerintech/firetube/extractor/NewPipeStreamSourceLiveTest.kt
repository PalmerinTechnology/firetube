package com.palmerintech.firetube.extractor

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException

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
            val stream = try {
                source.resolve(id)
            } catch (e: ExtractionException) {
                // YouTube asks datacenter IPs (like CI runners) to sign in, while real devices still
                // play. That says nothing about the extractor, so skip rather than fail.
                assumeTrue("YouTube bot check from this IP; skipping stream check", e.cause !is SignInConfirmNotBotException)
                throw e
            }
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
    fun liveStreamResolvesToAManifest() = runBlocking<Unit> {
        // 24/7 radio streams come and go (and get restarted under new ids): find one that's on now.
        val live = source.search("lofi hip hop radio", SearchFilter.ALL).items
            .filterIsInstance<SearchResult.TrackResult>().map { it.track }.firstOrNull { it.isLive }
        assumeTrue("no live stream in the search results", live != null)
        println("live -> ${live!!.id} ${live.title}")
        val stream = try {
            source.resolve(live.id)
        } catch (e: ExtractionException) {
            assumeTrue("YouTube bot check from this IP; skipping stream check", e.cause !is SignInConfirmNotBotException)
            throw e
        }
        assertTrue(stream.live)
        assertEquals(true, stream.track?.isLive)
        val manifest = OkHttpClient().newCall(Request.Builder().url(stream.url).build()).execute().use { resp ->
            assertTrue("HTTP ${resp.code} for the manifest", resp.isSuccessful)
            resp.body.string()
        }
        println("live -> ${manifest.lines().take(12)}")
        assertTrue(manifest.startsWith("#EXTM3U"))
    }

    @Test
    fun countryChartLoads() = runBlocking {
        val tracks = NewPipeStreamSource(chartCountry = "United States").trending()
        println("US trending -> ${tracks.size}: ${tracks.take(5).map { it.title }}")
        assertTrue(tracks.size >= 50)
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
