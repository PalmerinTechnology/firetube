package com.palmerintech.firetube.player

import com.palmerintech.firetube.data.AudioQuality
import com.palmerintech.firetube.extractor.Chapter
import com.palmerintech.firetube.extractor.ResolvedStream
import com.palmerintech.firetube.extractor.StreamSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns track ids into playable URLs just before they're needed, caching results until they
 * expire. Concurrent requests for the same id share one network call.
 */
class StreamResolver(
    private val source: StreamSource,
    private val quality: suspend () -> AudioQuality,
) {
    private val cache = ConcurrentHashMap<String, ResolvedStream>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Chapters by track id, kept after the URL expires (they don't change); only tracks that have some. */
    private val chapters = MutableStateFlow<Map<String, List<Chapter>>>(emptyMap())

    suspend fun resolve(trackId: String, forceRefresh: Boolean = false): ResolvedStream {
        // Keyed by quality too, so a URL resolved at the old quality is never served after a switch.
        val q = quality()
        val key = "$trackId@$q"
        if (!forceRefresh) fresh(key)?.let { return it }
        return locks.getOrPut(key) { Mutex() }.withLock {
            if (!forceRefresh) fresh(key)?.let { return@withLock it }
            source.resolve(trackId, preferLowBitrate = q == AudioQuality.DATA_SAVER)
                .also { cache[key] = it; keepChapters(trackId, it.chapters) }
        }
    }

    /** Related tracks from the last resolution, if any (used for autoplay without another request). */
    fun cachedRelated(trackId: String) = AudioQuality.entries.firstNotNullOfOrNull { cache["$trackId@$it"] }?.related

    /**
     * [trackId]'s chapters from when it was last resolved; empty until then, or when it has none.
     * A song played entirely from the cache or downloads isn't resolved, so shows none.
     */
    fun chapters(trackId: String): Flow<List<Chapter>> =
        chapters.map { it[trackId].orEmpty() }.distinctUntilChanged()

    private fun keepChapters(trackId: String, list: List<Chapter>) = chapters.update { all ->
        when {
            // Re-added at the end, so the least recently resolved is the first dropped.
            list.isNotEmpty() -> (all - trackId + (trackId to list)).let { if (it.size > MAX_CHAPTERED) it - it.keys.first() else it }
            trackId in all -> all - trackId
            else -> all
        }
    }

    fun invalidate(trackId: String) {
        AudioQuality.entries.forEach { cache.remove("$trackId@$it") }
    }

    /** Drops every resolved URL, e.g. after the audio quality setting changes. */
    fun clear() = cache.clear()

    private fun fresh(key: String) =
        cache[key]?.takeIf { it.expiresAtMillis > System.currentTimeMillis() }

    private companion object {
        /** Tracks whose chapters are kept in memory. */
        const val MAX_CHAPTERED = 50
    }
}
