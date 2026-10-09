package com.palmerintech.firetube.player.cast

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Reshapes a YouTube live stream's HLS for a Chromecast: picks the audio-only rendition out of
 * the master playlist, and points each segment of that rendition's playlist back at the phone.
 */
internal object LivePlaylist {
    private val audioMedia = Regex("""^#EXT-X-MEDIA:.*TYPE=AUDIO.*$""", RegexOption.MULTILINE)
    private val uri = Regex("""URI="([^"]+)"""")
    private val groupId = Regex("""GROUP-ID="([^"]+)"""")

    /** itag 234 is the 128 kbps AAC rendition (the live counterpart of 140); 233 is 48 kbps HE-AAC. */
    private const val PREFERRED_GROUP = "234"

    private const val MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE:"

    /** The audio rendition's playlist URL from a master playlist, the 128 kbps one when there's a choice. */
    fun audioPlaylistUrl(master: String): String? {
        val renditions = audioMedia.findAll(master).map { it.value }.toList()
        val pick = renditions.firstOrNull { groupId.find(it)?.groupValues?.get(1) == PREFERRED_GROUP } ?: renditions.lastOrNull()
        return pick?.let { uri.find(it)?.groupValues?.get(1) }
    }

    /**
     * [media] with every segment URL replaced by [local] of its sequence number. Returns the
     * rewritten playlist and each segment's original URL (resolved against [base], the playlist's
     * own URL) by sequence number.
     */
    fun rewrite(media: String, base: String, local: (Long) -> String): Pair<String, Map<Long, String>> {
        val baseUrl = base.toHttpUrlOrNull()
        var seq = media.lineSequence().firstOrNull { it.startsWith(MEDIA_SEQUENCE) }
            ?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L
        val segments = HashMap<Long, String>()
        val out = media.lineSequence().joinToString("\n") { line ->
            if (line.isBlank() || line.startsWith("#")) {
                line
            } else {
                segments[seq] = baseUrl?.resolve(line.trim())?.toString() ?: line.trim()
                local(seq++)
            }
        }
        return out to segments
    }
}
