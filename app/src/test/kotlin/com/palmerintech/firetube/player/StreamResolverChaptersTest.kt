package com.palmerintech.firetube.player

import com.palmerintech.firetube.data.AudioQuality
import com.palmerintech.firetube.extractor.Chapter
import com.palmerintech.firetube.extractor.Page
import com.palmerintech.firetube.extractor.PageToken
import com.palmerintech.firetube.extractor.PlaylistSummary
import com.palmerintech.firetube.extractor.ResolvedStream
import com.palmerintech.firetube.extractor.SearchFilter
import com.palmerintech.firetube.extractor.SearchResult
import com.palmerintech.firetube.extractor.StreamSource
import com.palmerintech.firetube.extractor.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StreamResolverChaptersTest {
    @get:Rule val tmp = TemporaryFolder()

    private val mix = listOf(Chapter("Intro", 0), Chapter("Drop", 60_000), Chapter("Outro", 120_000))

    /** Resolves every id; ids in [chaptered] come with [mix]'s chapters. */
    private class FakeSource(val chaptered: MutableSet<String>, val chapters: List<Chapter>) : StreamSource {
        var resolves = 0
        override suspend fun resolve(trackId: String, preferLowBitrate: Boolean): ResolvedStream {
            resolves++
            return ResolvedStream(
                trackId, "https://example.com/$trackId", "audio/mp4", 128, Long.MAX_VALUE,
                chapters = if (trackId in chaptered) chapters else emptyList(),
            )
        }
        override suspend fun search(query: String, filter: SearchFilter): Page<SearchResult> = error("unused")
        override suspend fun searchMore(query: String, filter: SearchFilter, token: PageToken): Page<SearchResult> = error("unused")
        override suspend fun suggestions(query: String): List<String> = error("unused")
        override suspend fun playlist(url: String): Pair<PlaylistSummary, Page<Track>> = error("unused")
        override suspend fun playlistMore(url: String, token: PageToken): Page<Track> = error("unused")
        override suspend fun trending(): List<Track> = error("unused")
    }

    @Test
    fun chaptersArriveWithTheStreamWithoutAnotherRequest() = runTest {
        val source = FakeSource(mutableSetOf("mix"), mix)
        val resolver = StreamResolver(source) { AudioQuality.HIGH }
        assertTrue(resolver.chapters("mix").first().isEmpty())
        resolver.resolve("mix")
        assertEquals(mix, resolver.chapters("mix").first())
        assertEquals(1, source.resolves)
        assertTrue(resolver.chapters("other").first().isEmpty())
    }

    @Test
    fun chaptersOutliveExpiredUrls() = runTest {
        val resolver = StreamResolver(FakeSource(mutableSetOf("mix"), mix)) { AudioQuality.HIGH }
        resolver.resolve("mix")
        resolver.invalidate("mix")
        resolver.clear()
        assertEquals(mix, resolver.chapters("mix").first())
    }

    @Test
    fun aRefreshWithoutChaptersDropsThem() = runTest {
        val chaptered = mutableSetOf("mix")
        val resolver = StreamResolver(FakeSource(chaptered, mix)) { AudioQuality.HIGH }
        resolver.resolve("mix")
        chaptered.clear()
        resolver.resolve("mix", forceRefresh = true)
        assertTrue(resolver.chapters("mix").first().isEmpty())
    }

    @Test
    fun onlyTheMostRecentFiftyAreKept() = runTest {
        val ids = (1..51).map { "t$it" }
        val resolver = StreamResolver(FakeSource(ids.toMutableSet(), mix)) { AudioQuality.HIGH }
        ids.forEach { resolver.resolve(it) }
        assertTrue(resolver.chapters("t1").first().isEmpty())
        assertEquals(mix, resolver.chapters("t2").first())
        assertEquals(mix, resolver.chapters("t51").first())
    }

    @Test
    fun aDownloadsChaptersComeBackWithoutAResolve() = runTest {
        val store = ChapterStore(tmp.newFolder("chapters"))
        val source = FakeSource(mutableSetOf("mix"), mix)
        // Downloaded (and its chapters saved) in an earlier run of the app...
        StreamResolver(source, store) { AudioQuality.HIGH }.run {
            resolve("mix")
            store.keepFor(setOf("mix"), this::knownChapters)
        }
        // ...then played from the download after a restart.
        val resolver = StreamResolver(source, store) { AudioQuality.HIGH }
        assertEquals(mix, resolver.chapters("mix").first())
        assertEquals(mix, resolver.knownChapters("mix"))
        assertEquals(1, source.resolves)
        assertTrue(resolver.chapters("other").first().isEmpty())
    }

    @Test
    fun aRemovedDownloadsChaptersAreGone() = runTest {
        val store = ChapterStore(tmp.newFolder("chapters"))
        store.save("mix", mix)
        store.keepFor(emptySet()) { null }
        assertTrue(StreamResolver(FakeSource(mutableSetOf(), mix), store) { AudioQuality.HIGH }.chapters("mix").first().isEmpty())
    }

    @Test
    fun aResolveWinsOverStoredChapters() = runTest {
        val store = ChapterStore(tmp.newFolder("chapters"))
        store.save("mix", listOf(Chapter("Old", 0), Chapter("List", 1_000)))
        val resolver = StreamResolver(FakeSource(mutableSetOf("mix"), mix), store) { AudioQuality.HIGH }
        resolver.resolve("mix")
        assertEquals(mix, resolver.chapters("mix").first())
    }
}
