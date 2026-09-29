package com.palmerintech.firetube.player

import com.palmerintech.firetube.data.AudioQuality
import com.palmerintech.firetube.extractor.ResolvedStream
import com.palmerintech.firetube.extractor.StreamSource
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

    suspend fun resolve(trackId: String, forceRefresh: Boolean = false): ResolvedStream {
        // Keyed by quality too, so a URL resolved at the old quality is never served after a switch.
        val q = quality()
        val key = "$trackId@$q"
        if (!forceRefresh) fresh(key)?.let { return it }
        return locks.getOrPut(key) { Mutex() }.withLock {
            if (!forceRefresh) fresh(key)?.let { return@withLock it }
            source.resolve(trackId, preferLowBitrate = q == AudioQuality.DATA_SAVER)
                .also { cache[key] = it }
        }
    }

    /** Related tracks from the last resolution, if any (used for autoplay without another request). */
    fun cachedRelated(trackId: String) = AudioQuality.entries.firstNotNullOfOrNull { cache["$trackId@$it"] }?.related

    fun invalidate(trackId: String) {
        AudioQuality.entries.forEach { cache.remove("$trackId@$it") }
    }

    /** Drops every resolved URL, e.g. after the audio quality setting changes. */
    fun clear() = cache.clear()

    private fun fresh(key: String) =
        cache[key]?.takeIf { it.expiresAtMillis > System.currentTimeMillis() }
}
