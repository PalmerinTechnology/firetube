package com.palmerintech.firetube.player

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.palmerintech.firetube.extractor.Track

/** Conversions between FireTube [Track]s and Media3 [MediaItem]s. */
object MediaItems {
    private const val SCHEME = "firetube"
    private const val HOST = "track"

    fun uriOf(trackId: String): Uri = "$SCHEME://$HOST/$trackId".toUri()

    fun trackIdOf(uri: Uri): String? =
        if (uri.scheme == SCHEME && uri.host == HOST) uri.lastPathSegment else null

    fun of(track: Track): MediaItem = MediaItem.Builder()
        .setMediaId(track.id)
        .setUri(uriOf(track.id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setArtworkUri(track.thumbnailUrl?.toUri())
                .setDurationMs(track.durationSeconds.takeIf { it > 0 }?.times(1000))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()

    /**
     * Items arriving from other processes (Android Auto, system media controls) lose their URI;
     * rebuild it from the media id.
     */
    fun withUri(item: MediaItem): MediaItem =
        if (item.localConfiguration != null) item else item.buildUpon().setUri(uriOf(item.mediaId)).build()

    fun trackOf(item: MediaItem): Track {
        val m = item.mediaMetadata
        return Track(
            id = item.mediaId,
            title = m.title?.toString().orEmpty(),
            artist = m.artist?.toString().orEmpty(),
            durationSeconds = (m.durationMs ?: 0L) / 1000,
            thumbnailUrl = m.artworkUri?.toString(),
        )
    }
}
