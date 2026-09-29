package com.palmerintech.firetube.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Community-submitted segments to skip (https://sponsor.ajay.app). For music the useful category
 * is "music_offtopic" — talking, skits and intros in music videos — plus the usual sponsor reads.
 */
class SponsorBlock(private val client: OkHttpClient) {

    /** Segments to skip, as millisecond ranges. Empty when there are none or the service is down. */
    suspend fun segments(videoId: String): List<LongRange> = withContext(Dispatchers.IO) {
        val url = "https://sponsor.ajay.app/api/skipSegments".toHttpUrl().newBuilder()
            .addQueryParameter("videoID", videoId)
            .addQueryParameter("categories", CATEGORIES)
            .build()
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList() // 404 = no segments
                json.decodeFromString<List<Segment>>(resp.body.string())
                    .filter { it.segment.size == 2 && it.actionType == "skip" }
                    .map { (it.segment[0] * 1000).toLong()..(it.segment[1] * 1000).toLong() }
            }
        } catch (e: Exception) {
            Timber.d(e, "SponsorBlock lookup failed for %s", videoId)
            emptyList()
        }
    }

    @Serializable
    private data class Segment(val segment: List<Double>, val category: String = "", val actionType: String = "skip")

    private companion object {
        const val CATEGORIES = """["music_offtopic","sponsor","selfpromo","interaction"]"""
        val json = Json { ignoreUnknownKeys = true }
    }
}
