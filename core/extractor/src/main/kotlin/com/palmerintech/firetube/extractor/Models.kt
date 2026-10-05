package com.palmerintech.firetube.extractor

/** A playable item (song / video) as FireTube sees it, independent of any extractor library. */
data class Track(
    /** Stable id — the YouTube video id. */
    val id: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
) {
    val url: String get() = "https://www.youtube.com/watch?v=$id"
}

data class PlaylistSummary(
    val url: String,
    val title: String,
    val owner: String,
    val trackCount: Long,
    val thumbnailUrl: String?,
)

/** One page of results plus an opaque token for the next page (null when there are no more). */
data class Page<T>(val items: List<T>, val next: PageToken?)

/** Opaque continuation handle; only the [StreamSource] that produced it knows what's inside. */
class PageToken internal constructor(internal val value: Any)

/** A search hit: either something playable or a playlist to open. */
sealed interface SearchResult {
    data class TrackResult(val track: Track) : SearchResult
    data class PlaylistResult(val playlist: PlaylistSummary) : SearchResult
}

enum class SearchFilter { SONGS, VIDEOS, PLAYLISTS }

/** A resolved, directly playable audio stream. URLs expire (YouTube: ~6h), so resolve just in time. */
data class ResolvedStream(
    val trackId: String,
    val url: String,
    val mimeType: String?,
    val bitrate: Int,
    /** Epoch millis after which [url] should be considered stale. */
    val expiresAtMillis: Long,
    val related: List<Track> = emptyList(),
    /** The track's own details (useful when all we had was an id, e.g. from a shared link). */
    val track: Track? = null,
    /** Sections of a long video (a DJ mix, a full album), in order; empty when it has none. */
    val chapters: List<Chapter> = emptyList(),
)

/** A titled section of a track, starting [startMs] into it. */
data class Chapter(val title: String, val startMs: Long)

class ExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** True when retrying later won't help (removed, private, age-restricted, region-blocked). */
    var permanent: Boolean = false
        internal set
}
