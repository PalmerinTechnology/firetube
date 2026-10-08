package com.palmerintech.firetube.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Songs play through [songs] (downloads → cache → network); live streams are HLS straight from
 * [network], since there's nothing worth caching in a stream that never replays.
 */
@UnstableApi
class LiveMediaSourceFactory(songs: DataSource.Factory, network: DataSource.Factory) : MediaSource.Factory {
    private val default = DefaultMediaSourceFactory(songs)
    private val hls = HlsMediaSource.Factory(network)

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = apply {
        default.setDrmSessionManagerProvider(provider)
        hls.setDrmSessionManagerProvider(provider)
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = apply {
        default.setLoadErrorHandlingPolicy(policy)
        hls.setLoadErrorHandlingPolicy(policy)
    }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri
        return if (uri != null && MediaItems.isLiveUri(uri)) hls.createMediaSource(mediaItem) else default.createMediaSource(mediaItem)
    }
}
