package com.palmerintech.firetube.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeTrendingMusicLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.Page as NpPage

/** [StreamSource] backed by NewPipeExtractor's YouTube service. */
class NewPipeStreamSource(
    client: OkHttpClient = OkHttpDownloader.defaultClient(),
    localization: Localization = Localization.DEFAULT,
    country: ContentCountry = ContentCountry.DEFAULT,
) : StreamSource {

    init {
        NewPipe.init(OkHttpDownloader(client), localization, country)
    }

    private val yt = ServiceList.YouTube

    override suspend fun search(query: String, filter: SearchFilter): Page<SearchResult> = io {
        val handler = yt.searchQHFactory.fromQuery(query, listOf(filter.contentFilter()), "")
        val info = SearchInfo.getInfo(yt, handler)
        Page(info.relatedItems.mapNotNull { it.toSearchResult() }, info.nextPage?.let(::PageToken))
    }

    override suspend fun searchMore(query: String, filter: SearchFilter, token: PageToken): Page<SearchResult> = io {
        val handler = yt.searchQHFactory.fromQuery(query, listOf(filter.contentFilter()), "")
        val page = SearchInfo.getMoreItems(yt, handler, token.value as NpPage)
        Page(page.items.mapNotNull { it.toSearchResult() }, page.nextPage?.let(::PageToken))
    }

    override suspend fun suggestions(query: String): List<String> = io {
        yt.suggestionExtractor.suggestionList(query)
    }

    override suspend fun resolve(trackId: String, preferLowBitrate: Boolean): ResolvedStream = io {
        val info = StreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=$trackId")
        val related = info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { it.toTrack() }
        val track = Track(trackId, info.name, info.uploaderName.orEmpty().removeSuffix(" - Topic"), info.duration, info.thumbnails.best())
        if (info.streamType.isLive()) {
            throw ExtractionException("Live streams aren't supported").apply { permanent = true }
        }
        val audio = pickAudio(info.audioStreams, preferLowBitrate)
            ?: throw ExtractionException("No playable audio stream for $trackId")
        ResolvedStream(
            trackId = trackId,
            url = audio.content,
            mimeType = audio.format?.mimeType,
            bitrate = audio.averageBitrate,
            expiresAtMillis = expiryOf(audio.content),
            related = related,
            track = track,
        )
    }

    override suspend fun playlist(url: String): Pair<PlaylistSummary, Page<Track>> = io {
        val info = PlaylistInfo.getInfo(yt, url)
        val summary = PlaylistSummary(
            url = info.url,
            title = info.name,
            owner = info.uploaderName.orEmpty(),
            trackCount = info.streamCount,
            thumbnailUrl = info.thumbnails.best(),
        )
        summary to Page(info.relatedItems.mapNotNull { it.toTrack() }, info.nextPage?.let(::PageToken))
    }

    override suspend fun playlistMore(url: String, token: PageToken): Page<Track> = io {
        val page = PlaylistInfo.getMoreItems(yt, url, token.value as NpPage)
        Page(page.items.mapNotNull { it.toTrack() }, page.nextPage?.let(::PageToken))
    }

    override suspend fun trending(): List<Track> {
        val kiosk = runCatching {
            io {
                val extractor = yt.kioskList.getExtractorById(YoutubeTrendingMusicLinkHandlerFactory.KIOSK_ID, null)
                KioskInfo.getInfo(extractor).relatedItems.mapNotNull { it.toTrack() }
            }
        }.getOrDefault(emptyList())
        // The trending-music kiosk is often empty; YouTube's global chart playlist is a reliable stand-in.
        return kiosk.ifEmpty { playlist(TOP_SONGS_GLOBAL).second.items }
    }

    /** Runs blocking NewPipe calls off the main thread and maps its exceptions to ours. */
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: ExtractionException) {
            throw e
        } catch (e: Exception) {
            throw ExtractionException(e.message ?: e.javaClass.simpleName, e).apply {
                permanent = e is AgeRestrictedContentException || e is GeographicRestrictionException ||
                    e is PrivateContentException || e is PaidContentException ||
                    e.javaClass == ContentNotAvailableException::class.java
            }
        }
    }

    private fun SearchFilter.contentFilter() = when (this) {
        SearchFilter.SONGS -> YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
        SearchFilter.VIDEOS -> YoutubeSearchQueryHandlerFactory.VIDEOS
        SearchFilter.PLAYLISTS -> YoutubeSearchQueryHandlerFactory.PLAYLISTS
    }

    private fun InfoItem.toSearchResult(): SearchResult? = when (this) {
        is StreamInfoItem -> toTrack()?.let(SearchResult::TrackResult)
        is PlaylistInfoItem -> SearchResult.PlaylistResult(
            PlaylistSummary(url, name, uploaderName.orEmpty(), streamCount, thumbnails.best()),
        )
        else -> null
    }

    private fun StreamInfoItem.toTrack(): Track? {
        if (streamType.isLive()) return null
        val id = videoId(url) ?: return null
        return Track(id, name, uploaderName.orEmpty().removeSuffix(" - Topic"), duration, thumbnails.best())
    }

    private fun StreamType?.isLive() = this == StreamType.LIVE_STREAM || this == StreamType.AUDIO_LIVE_STREAM

    companion object {
        /** YouTube Music's "Top 100 Songs Global" chart. */
        const val TOP_SONGS_GLOBAL = "https://www.youtube.com/playlist?list=PL4fGSI1pDJn6puJdseH2Rt9sMvt9E2M4i"

        private val VIDEO_ID = Regex("(?:v=|youtu\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})")

        fun videoId(url: String): String? = VIDEO_ID.find(url)?.groupValues?.get(1)

        /**
         * Prefer progressive (single-file) streams — ExoPlayer plays them directly and they cache
         * cleanly. Among those prefer AAC/M4A (plays everywhere, incl. Cast), then by bitrate.
         */
        internal fun pickAudio(streams: List<AudioStream>, preferLowBitrate: Boolean = false): AudioStream? {
            val progressive = streams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            // Skip dubbed / descriptive audio tracks when an original exists.
            val original = progressive.filter { it.audioTrackType == null || it.audioTrackType?.name == "ORIGINAL" }
            val byPreference = compareBy<AudioStream> { if (it.format == MediaFormat.M4A) 1 else 0 }
                .thenBy { if (preferLowBitrate) -it.averageBitrate else it.averageBitrate }
            return original.ifEmpty { progressive }.maxWithOrNull(byPreference)
        }

        internal fun expiryOf(url: String): Long {
            val expireSeconds = url.toHttpUrlOrNull()?.queryParameter("expire")?.toLongOrNull()
            // Refresh a bit early; default to 1h if the URL doesn't say.
            return expireSeconds?.let { it * 1000 - 10 * 60_000 } ?: (System.currentTimeMillis() + 60 * 60_000)
        }

        internal fun List<Image>.best(): String? =
            filter { it.height <= 720 || it.height == Image.HEIGHT_UNKNOWN }.maxByOrNull { it.height }?.url
                ?: firstOrNull()?.url
    }
}
