package com.palmerintech.firetube.player.cast

import android.net.Uri
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.common.images.WebImage
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.player.MediaItems
import org.json.JSONObject

/** Converts queue items to Cast items that point at [CastProxyServer], and back. */
@UnstableApi
class CastItemConverter(private val server: CastProxyServer) : MediaItemConverter {

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val track = MediaItems.trackOf(mediaItem)
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, track.title)
            putString(MediaMetadata.KEY_ARTIST, track.artist)
            track.thumbnailUrl?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val info = MediaInfo.Builder(server.urlFor(track.id) ?: MediaItems.uriOf(track.id).toString())
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            // FireTube prefers AAC/M4A streams, which every Cast device plays.
            .setContentType("audio/mp4")
            .setMetadata(metadata)
            .apply { if (track.durationSeconds > 0) setStreamDuration(track.durationSeconds * 1000) }
            .setCustomData(
                JSONObject()
                    .put(KEY_ID, track.id)
                    .put(KEY_DURATION, track.durationSeconds)
                    .put(KEY_THUMB, track.thumbnailUrl ?: ""),
            )
            .build()
        return MediaQueueItem.Builder(info).build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val info = mediaQueueItem.media
        val custom = info?.customData
        // Items we didn't create (e.g. queued by another sender) lack custom data; fall back to the URL.
        val id = custom?.optString(KEY_ID)?.takeIf { it.isNotEmpty() }
            ?: info?.contentId?.let { TRACK_IN_URL.find(it)?.groupValues?.get(1) }.orEmpty()
        val metadata = info?.metadata
        return MediaItems.of(
            Track(
                id = id,
                title = metadata?.getString(MediaMetadata.KEY_TITLE).orEmpty(),
                artist = metadata?.getString(MediaMetadata.KEY_ARTIST).orEmpty(),
                durationSeconds = custom?.optLong(KEY_DURATION) ?: 0,
                thumbnailUrl = custom?.optString(KEY_THUMB)?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    private companion object {
        const val KEY_ID = "firetubeId"
        const val KEY_DURATION = "durationSeconds"
        const val KEY_THUMB = "thumb"
        val TRACK_IN_URL = Regex("/track/([A-Za-z0-9_-]{11})")
    }
}
