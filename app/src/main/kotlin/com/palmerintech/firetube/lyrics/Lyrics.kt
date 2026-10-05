package com.palmerintech.firetube.lyrics

import com.palmerintech.firetube.extractor.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.math.abs

/** What a lyrics lookup found for a track. */
sealed interface LyricsResult {
    data class Synced(val lines: List<LrcLine>) : LyricsResult
    data class Plain(val text: String) : LyricsResult
    data object Instrumental : LyricsResult
    data object NotFound : LyricsResult
}

/**
 * Song lyrics from LRCLIB (https://lrclib.net), a free community database with no key or account.
 * YouTube titles are guessed into artist + song by [TitleCleaner]; a few variants are tried and
 * results whose length matches the track's are preferred, since only those have usable timings.
 * Results (including "nothing found") are kept in memory per track id, so reopening is instant.
 */
class Lyrics(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val baseUrl: HttpUrl = "https://lrclib.net/api/".toHttpUrl(),
) {
    private val cache = object : LinkedHashMap<String, LyricsResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsResult>) = size > CACHE_SIZE
    }

    fun cached(trackId: String): LyricsResult? = synchronized(cache) { cache[trackId] }

    /** Lyrics for [track]. Throws [IOException] when LRCLIB can't be reached (nothing is cached then). */
    suspend fun lookup(track: Track): LyricsResult {
        cached(track.id)?.let { return it }
        val result = withContext(Dispatchers.IO) { fetch(track) }
        synchronized(cache) { cache[track.id] = result }
        return result
    }

    private fun fetch(track: Track): LyricsResult {
        val queries = TitleCleaner.queries(track.title, track.artist)
        val duration = track.durationSeconds.toDouble()
        var fallback: Record? = null
        // An exact match (artist, song and length) is the best answer; LRCLIB also looks it up
        // from other sources when it doesn't have it yet.
        queries.firstOrNull()?.takeIf { duration > 0 }?.let { q ->
            get("get", "track_name" to q.title, "artist_name" to q.artist, "duration" to track.durationSeconds.toString())
                ?.let { body -> json.decodeFromString<Record>(body) }
                ?.takeIf { it.hasLyrics && matches(it, q) }
                ?.let { if (closeLength(it, duration)) return it.toResult(true) else fallback = it }
        }
        // Then a search per variant, and finally a free-text one in case the split was wrong.
        val searches = queries.map { q -> q to arrayOf("track_name" to q.title, "artist_name" to q.artist) } +
            queries.take(1).map { q -> q to arrayOf("q" to "${q.artist} ${q.title}") }
        for ((q, params) in searches) {
            val hits = get("search", *params)?.let { json.decodeFromString<List<Record>>(it) }.orEmpty()
            val best = pick(hits, q, duration) ?: continue
            if (closeLength(best, duration)) return best.toResult(true)
            if (fallback == null) fallback = best
        }
        // Right song, different cut (radio edit, extended video): the words fit, the timings don't.
        return fallback?.toResult(false) ?: LyricsResult.NotFound
    }

    /** The response body, or null for "not found"; throws for network or server failures. */
    private fun get(path: String, vararg params: Pair<String, String>): String? {
        val url = baseUrl.newBuilder().addPathSegments(path)
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        client.newCall(request).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IOException("LRCLIB returned ${resp.code}")
            return resp.body.string()
        }
    }

    @Serializable
    internal data class Record(
        val trackName: String = "",
        val artistName: String = "",
        val duration: Double = 0.0,
        val instrumental: Boolean = false,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
    ) {
        // Community data: a few entries hold a placeholder ("probe", "...") rather than lyrics.
        private val words get() = plainLyrics?.takeIf { it.trim().length >= MIN_CHARS }
        private val timed get() = syncedLyrics?.takeIf { it.trim().length >= MIN_CHARS }
        val synced get() = timed != null
        val hasWords get() = synced || words != null
        val hasLyrics get() = instrumental || hasWords

        fun toResult(trustTimings: Boolean): LyricsResult {
            if (!hasWords) return if (instrumental) LyricsResult.Instrumental else LyricsResult.NotFound
            val lines = timed?.let(Lrc::parse).orEmpty()
            if (trustTimings && lines.any { it.text.isNotEmpty() }) return LyricsResult.Synced(lines)
            val plain = words ?: lines.joinToString("\n") { it.text }.trim().takeIf { it.isNotEmpty() }
            return plain?.let(LyricsResult::Plain) ?: LyricsResult.NotFound
        }
    }

    internal companion object {
        const val CACHE_SIZE = 100
        /** How far (in seconds) a result's length may be from the track's for its timings to fit. */
        const val LENGTH_TOLERANCE = 5.0
        /** Shorter than this isn't a song's lyrics. */
        const val MIN_CHARS = 20
        val json = Json { ignoreUnknownKeys = true }

        fun closeLength(r: Record, duration: Double) = duration <= 0 || abs(r.duration - duration) <= LENGTH_TOLERANCE

        /** LRCLIB's search is fuzzy; only accept a result whose song name resembles the query's. */
        fun matches(r: Record, q: LyricsQuery): Boolean {
            val want = TitleCleaner.normalize(TitleCleaner.cleanTitle(q.title))
            val got = TitleCleaner.normalize(TitleCleaner.cleanTitle(r.trackName))
            return want.isEmpty() || got.isEmpty() || want in got || got in want
        }

        /** The best usable hit: right name, then right length, words over "instrumental", synced, closest length. */
        fun pick(hits: List<Record>, q: LyricsQuery, duration: Double): Record? = hits
            .filter { it.hasLyrics && matches(it, q) }
            .sortedWith(
                compareByDescending<Record> { closeLength(it, duration) }
                    .thenByDescending { it.hasWords }
                    .thenByDescending { it.synced }
                    .thenBy { if (duration > 0) abs(it.duration - duration) else 0.0 },
            )
            .firstOrNull()
    }
}
