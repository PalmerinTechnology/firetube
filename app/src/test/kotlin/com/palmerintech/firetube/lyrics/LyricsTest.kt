package com.palmerintech.firetube.lyrics

import com.palmerintech.firetube.extractor.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The LRCLIB client against canned responses; no request leaves the machine. */
class LyricsTest {

    // Filled from OkHttp's threads.
    private val requests: MutableList<HttpUrl> = Collections.synchronizedList(mutableListOf())
    private val userAgents: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    /** Answers each request with [respond] (status to body); null = 404. */
    private fun lyrics(respond: (HttpUrl) -> Pair<Int, String>?): Lyrics {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            requests += req.url
            userAgents += req.header("User-Agent")
            val (code, body) = respond(req.url) ?: (404 to """{"code":404,"name":"TrackNotFound"}""")
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return Lyrics(client, "FireTube/test (https://github.com/PalmerinTek/firetube)")
    }

    private val track = Track("vid1", "Artist - Song (Official Video)", "ArtistVEVO", 200, null)

    private fun record(
        name: String = "Song", duration: Double = 200.0, synced: String? = SYNCED, plain: String? = "Hello\nWorld",
        instrumental: Boolean = false, artist: String = "Artist",
    ) =
        """{"id":1,"trackName":"$name","artistName":"$artist","albumName":"A","duration":$duration,"instrumental":$instrumental,""" +
            """"plainLyrics":${plain?.let { "\"${it.replace("\n", "\\n")}\"" } ?: "null"},""" +
            """"syncedLyrics":${synced?.let { "\"${it.replace("\n", "\\n")}\"" } ?: "null"}}"""

    @Test
    fun `exact match returns synced lyrics with the cleaned names and a user agent`() = runTest {
        val l = lyrics { url -> if (url.encodedPath == "/api/get") 200 to record() else null }
        val result = l.lookup(track)
        assertEquals(LyricsResult.Synced(listOf(LrcLine(1_000, "Hello"), LrcLine(2_000, "World"))), result)
        val get = requests.single()
        assertEquals("lrclib.net", get.host)
        assertEquals("Song", get.queryParameter("track_name"))
        assertEquals("Artist", get.queryParameter("artist_name"))
        assertEquals("200", get.queryParameter("duration"))
        assertTrue(userAgents.single()!!.startsWith("FireTube/"))
    }

    @Test
    fun `search prefers the result whose length matches, then synced`() = runTest {
        val l = lyrics { url ->
            when (url.encodedPath) {
                "/api/search" -> 200 to "[" + listOf(
                    record(duration = 260.0, synced = "[00:09.00]Long version"),
                    record(duration = 203.0, synced = null, plain = "Plain close"),
                    record(duration = 198.0, synced = "[00:05.00]Close and synced"),
                ).joinToString(",") + "]"
                else -> null
            }
        }
        assertEquals(LyricsResult.Synced(listOf(LrcLine(5_000, "Close and synced"))), l.lookup(track))
    }

    @Test
    fun `a result of a different length only gives plain lyrics`() = runTest {
        val l = lyrics { url ->
            if (url.encodedPath == "/api/search") 200 to "[" + record(duration = 240.0, plain = null, synced = "[00:01.00]Words\n[00:02.00]More") + "]" else null
        }
        assertEquals(LyricsResult.Plain("Words\nMore"), l.lookup(track))
        // Everything was tried looking for a better match: get, a search per variant (just one
        // here, as title and channel agree on the artist), then free text.
        assertEquals(listOf("/api/get", "/api/search", "/api/search"), requests.map { it.encodedPath })
        assertEquals("Artist Song", requests.last().queryParameter("q"))
    }

    @Test
    fun `results for a different song are ignored`() = runTest {
        val l = lyrics { url -> if (url.encodedPath == "/api/search") 200 to "[" + record(name = "Something Else") + "]" else null }
        assertEquals(LyricsResult.NotFound, l.lookup(track))
    }

    @Test
    fun `plain-only and instrumental results`() = runTest {
        val plain = lyrics { url -> if (url.encodedPath == "/api/get") 200 to record(synced = null, plain = "Just words, no timings") else null }
        assertEquals(LyricsResult.Plain("Just words, no timings"), plain.lookup(track))
        val instrumental = lyrics { url -> if (url.encodedPath == "/api/get") 200 to record(synced = null, plain = null, instrumental = true) else null }
        assertEquals(LyricsResult.Instrumental, instrumental.lookup(track))
    }

    @Test
    fun `placeholder entries are passed over for real lyrics`() = runTest {
        val l = lyrics { url ->
            when (url.encodedPath) {
                "/api/get" -> 200 to record(synced = "[00:00.00]probe", plain = "probe")
                "/api/search" -> 200 to "[" + record(duration = 201.0) + "]"
                else -> null
            }
        }
        assertEquals(LyricsResult.Synced(listOf(LrcLine(1_000, "Hello"), LrcLine(2_000, "World"))), l.lookup(track))
    }

    @Test
    fun `results are cached per track, including not found`() = runTest {
        val l = lyrics { null }
        assertEquals(LyricsResult.NotFound, l.lookup(track))
        val made = requests.size
        assertEquals(LyricsResult.NotFound, l.lookup(track))
        assertEquals(LyricsResult.NotFound, l.cached(track.id))
        assertEquals(made, requests.size)
    }

    @Test
    fun `network and server failures throw and are not cached`() = runTest {
        var offline = true
        val l = lyrics { if (offline) throw UnknownHostException("lrclib.net") else 500 to "oops" }
        try {
            l.lookup(track); fail()
        } catch (_: UnknownHostException) {
        }
        offline = false
        try {
            l.lookup(track); fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("500"))
        }
        assertEquals(null, l.cached(track.id))
    }

    @Test
    fun `unknown length skips the exact lookup and accepts any length`() = runTest {
        val l = lyrics { url -> if (url.encodedPath == "/api/search") 200 to "[" + record(duration = 321.0) + "]" else null }
        val result = l.lookup(track.copy(id = "vid2", durationSeconds = 0))
        assertTrue(result is LyricsResult.Synced)
        assertEquals("/api/search", requests.first().encodedPath)
    }

    @Test
    fun `Topic uploads look up the song, not its version label`() = runTest {
        val sun = Track("sun", "Here Comes The Sun - Remastered 2009", "The Beatles - Topic", 186, null)
        val l = lyrics { url ->
            if (url.encodedPath == "/api/get") 200 to record(name = "Here Comes The Sun - Remastered 2009", artist = "The Beatles", duration = 185.0) else null
        }
        assertTrue(l.lookup(sun) is LyricsResult.Synced)
        assertEquals("Here Comes The Sun", requests.single().queryParameter("track_name"))
        assertEquals("The Beatles", requests.single().queryParameter("artist_name"))
    }

    @Test
    fun `a version word never pulls in another song's lyrics`() = runTest {
        val hallelujah = Track("hal", "Hallelujah - Live", "Jeff Buckley - Topic", 420, null)
        val wrong = "[" + listOf(
            record(name = "Live Forever", artist = "Oasis", duration = 420.0),
            record(name = "Alive", artist = "Pearl Jam", duration = 421.0),
            record(name = "Live", artist = "Jeff Buckley", duration = 420.0),
        ).joinToString(",") + "]"
        val miss = lyrics { url -> if (url.encodedPath == "/api/search") 200 to wrong else null }
        assertEquals(LyricsResult.NotFound, miss.lookup(hallelujah))
        assertTrue(requests.all { it.queryParameter("track_name") ?: it.queryParameter("q") != "Live" })

        val hit = lyrics { url ->
            if (url.encodedPath == "/api/search") 200 to "[" + record(name = "Hallelujah", artist = "Jeff Buckley", duration = 418.0) + "]" else null
        }
        assertTrue(hit.lookup(hallelujah) is LyricsResult.Synced)
    }

    @Test
    fun `free-text and different-length results must be by the right artist`() = runTest {
        // Only the free-text search answers, with the right song name by someone else.
        val l = lyrics { url -> if (url.queryParameter("q") != null) 200 to "[" + record(artist = "Someone Else") + "]" else null }
        assertEquals(LyricsResult.NotFound, l.lookup(track))
        val fallback = lyrics { url -> if (url.encodedPath == "/api/search") 200 to "[" + record(duration = 260.0, artist = "Cover Band") + "]" else null }
        assertEquals(LyricsResult.NotFound, fallback.lookup(track))
    }

    @Test
    fun `label and lyric channel uploads are looked up by the artist in the title`() = runTest {
        // LRCLIB only knows the song under its artist; any query by the channel finds nothing.
        fun lrclib(song: String, artist: String) = lyrics { url ->
            val byName = url.queryParameter("track_name") == song && url.queryParameter("artist_name") == artist
            when {
                url.encodedPath == "/api/get" && byName -> 200 to record(name = song, artist = artist)
                url.encodedPath == "/api/search" && byName -> 200 to "[" + record(name = song, artist = artist) + "]"
                else -> null
            }
        }
        val synced = LyricsResult.Synced(listOf(LrcLine(1_000, "Hello"), LrcLine(2_000, "World")))
        val dynamite = Track("dyn", "BTS (방탄소년단) 'Dynamite' Official MV", "HYBE LABELS", 200, null)
        assertEquals(synced, lrclib("Dynamite", "BTS").lookup(dynamite))
        val clouds = Track("7c", "Shawn Mendes - Treat You Better (Lyrics)", "7clouds", 200, null)
        assertEquals(synced, lrclib("Treat You Better", "Shawn Mendes").lookup(clouds))
        // "Song - Artist": found by the second variant's search.
        val swapped = Track("sw", "Birds of a Feather - Billie Eilish | Lyrics", "Dan Music Lyrics", 200, null)
        assertEquals(synced, lrclib("Birds of a Feather", "Billie Eilish").lookup(swapped))
        // Still strict: the right song by the channel isn't taken.
        assertEquals(LyricsResult.NotFound, lrclib("Dynamite", "HYBE LABELS").lookup(Track("d2", dynamite.title, dynamite.artist, 200, null)))
    }

    @Test
    fun `a bracketed version of a one-word song matches, by the same artist only`() = runTest {
        val umbrella = Track("umb", "Rihanna - Umbrella (Orange Version)", "RihannaVEVO", 260, null)
        val hit = lyrics { url ->
            if (url.encodedPath == "/api/search") 200 to "[" + record(name = "Umbrella", artist = "Rihanna", duration = 258.0) + "]" else null
        }
        assertTrue(hit.lookup(umbrella) is LyricsResult.Synced)
        val other = lyrics { url ->
            if (url.encodedPath == "/api/search") 200 to "[" + record(name = "Umbrella", artist = "The Baseballs", duration = 258.0) + "]" else null
        }
        assertEquals(LyricsResult.NotFound, other.lookup(umbrella))
    }

    @Test
    fun `an instrumental entry doesn't beat words for a vocal track`() = runTest {
        val l = lyrics { url ->
            when (url.encodedPath) {
                "/api/get" -> 200 to record(synced = null, plain = null, instrumental = true) // right length, no words
                "/api/search" -> 200 to "[" + listOf(
                    record(synced = null, plain = null, instrumental = true),
                    record(duration = 230.0, synced = null, plain = "The real words of the song"),
                ).joinToString(",") + "]"
                else -> null
            }
        }
        assertEquals(LyricsResult.Plain("The real words of the song"), l.lookup(track))
        // With nothing else to go on, it still says instrumental.
        val only = lyrics { url -> if (url.encodedPath == "/api/get") 200 to record(synced = null, plain = null, instrumental = true) else null }
        assertEquals(LyricsResult.Instrumental, only.lookup(track))
    }

    @Test
    fun `instrumental and vocal versions don't get each other's result`() = runTest {
        val vocalEntries = lyrics { url ->
            if (url.encodedPath == "/api/search") 200 to "[" + record(name = "Song") + "]" else null
        }
        val karaoke = Track("kar", "Artist - Song (Instrumental)", "ArtistVEVO", 200, null)
        assertEquals(LyricsResult.NotFound, vocalEntries.lookup(karaoke))

        val instrumentalEntries = lyrics { url ->
            if (url.encodedPath == "/api/search") 200 to "[" + record(name = "Song (Instrumental)", synced = null, plain = null, instrumental = true) + "]" else null
        }
        assertEquals(LyricsResult.Instrumental, instrumentalEntries.lookup(karaoke))
        assertEquals(LyricsResult.NotFound, instrumentalEntries.lookup(track))
    }

    @Test
    fun `nulls in a result fall back to defaults`() = runTest {
        val l = lyrics { url ->
            if (url.encodedPath == "/api/search") {
                200 to """[{"id":1,"trackName":"Song","artistName":"Artist","duration":null,"instrumental":null,"plainLyrics":null,"syncedLyrics":"$SYNCED_JSON"}]"""
            } else {
                null
            }
        }
        assertTrue(l.lookup(track.copy(id = "nulls", durationSeconds = 0)) is LyricsResult.Synced)
    }

    @Test
    fun `cancelling a lookup cancels its request and caches nothing`() = runBlocking {
        val calls = mutableListOf<Call>()
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            synchronized(calls) { calls += chain.call() }
            release.await(5, TimeUnit.SECONDS)
            throw IOException("Canceled")
        }.build()
        val l = Lyrics(client, "FireTube/test")
        val job = launch(Dispatchers.Default) { l.lookup(track) }
        waitFor { synchronized(calls) { calls.isNotEmpty() } }
        job.cancel()
        // The coroutine ends at once, without waiting for the stuck request.
        withTimeout(1_000) { job.join() }
        assertTrue(calls.single().isCanceled())
        release.countDown()
        assertEquals(null, l.cached(track.id))
        assertEquals(1, calls.size)
    }

    @Test
    fun `asking twice at once makes one set of requests`() = runBlocking {
        val started = AtomicInteger()
        val release = CountDownLatch(1)
        val l = lyrics { url ->
            started.incrementAndGet()
            release.await(5, TimeUnit.SECONDS)
            if (url.encodedPath == "/api/get") 200 to record() else null
        }
        val first = async(Dispatchers.Default) { l.lookup(track) }
        waitFor { started.get() == 1 }
        val second = async(Dispatchers.Default) { l.lookup(track) }
        Thread.sleep(200) // time for a second request, if it were going to make one
        assertEquals(1, started.get())
        release.countDown()
        assertEquals(first.await(), second.await())
        assertEquals(1, requests.size)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    private companion object {
        const val SYNCED_JSON = "[00:01.00]Hello\\n[00:02.00]World"
        const val SYNCED = "[00:01.00]Hello\n[00:02.00]World"
    }
}
