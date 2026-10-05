package com.palmerintech.firetube.lyrics

import com.palmerintech.firetube.extractor.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException
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

    /** One lock per track being looked up, so asking twice at once makes one set of requests. */
    private val inFlight = mutableMapOf<String, Mutex>()

    fun cached(trackId: String): LyricsResult? = synchronized(cache) { cache[trackId] }

    /**
     * Lyrics for [track]. Throws [IOException] when LRCLIB can't be reached (nothing is cached
     * then). Cancelling the caller cancels the request in flight.
     */
    suspend fun lookup(track: Track): LyricsResult {
        cached(track.id)?.let { return it }
        val lock = synchronized(inFlight) { inFlight.getOrPut(track.id) { Mutex() } }
        try {
            // A second caller waits here for the first, then finds its result cached. If the
            // first was cancelled or failed, the second looks it up itself.
            return lock.withLock {
                cached(track.id) ?: withContext(Dispatchers.IO) { fetch(track) }
                    .also { result -> synchronized(cache) { cache[track.id] = result } }
            }
        } finally {
            synchronized(inFlight) { if (!lock.isLocked) inFlight.remove(track.id, lock) }
        }
    }

    private suspend fun fetch(track: Track): LyricsResult {
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

    /**
     * The response body, or null for "not found"; throws for network or server failures. The
     * call is cancelled with the coroutine, so closing the sheet stops the lookup.
     */
    private suspend fun get(path: String, vararg params: Pair<String, String>): String? {
        currentCoroutineContext().ensureActive()
        val url = baseUrl.newBuilder().addPathSegments(path)
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) = cont.resumeWith(
                    runCatching {
                        response.use { resp ->
                            when {
                                resp.code == 404 -> null
                                !resp.isSuccessful -> throw IOException("LRCLIB returned ${resp.code}")
                                else -> resp.body.string()
                            }
                        }
                    },
                )
            })
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
        // coerceInputValues: a null where a value is expected (duration, names) means the default.
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        fun closeLength(r: Record, duration: Double) = duration <= 0 || abs(r.duration - duration) <= LENGTH_TOLERANCE

        /**
         * LRCLIB's search is fuzzy (and free text ignores the artist), so a result must be by the
         * queried artist and have the queried song's name.
         */
        fun matches(r: Record, q: LyricsQuery): Boolean =
            TitleCleaner.sameSong(q.title, r.trackName) && TitleCleaner.sameArtist(q.artist, r.artistName)

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
