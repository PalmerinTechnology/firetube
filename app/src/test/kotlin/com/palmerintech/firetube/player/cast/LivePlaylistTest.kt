package com.palmerintech.firetube.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LivePlaylistTest {
    private companion object {
        const val BASE = "https://manifest.googlevideo.com/hls_playlist/itag/234/index.m3u8"
    }

    // Trimmed from a real YouTube live master playlist.
    private val master = """
        #EXTM3U
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/hls_playlist/itag/233/index.m3u8",TYPE=AUDIO,GROUP-ID="233",NAME="Default",DEFAULT=YES,AUTOSELECT=YES
        #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/hls_playlist/itag/234/index.m3u8",TYPE=AUDIO,GROUP-ID="234",NAME="Default",DEFAULT=YES,AUTOSELECT=YES
        #EXT-X-STREAM-INF:BANDWIDTH=546239,CODECS="avc1.4D4015,mp4a.40.5",RESOLUTION=426x240,AUDIO="233"
        https://manifest.googlevideo.com/hls_playlist/itag/229/index.m3u8
    """.trimIndent()

    @Test
    fun picksThe128kAudioRendition() {
        assertEquals("https://manifest.googlevideo.com/hls_playlist/itag/234/index.m3u8", LivePlaylist.audioPlaylistUrl(master))
    }

    @Test
    fun fallsBackToAnyAudioRendition() {
        val only233 = master.lines().filterNot { "GROUP-ID=\"234\"" in it }.joinToString("\n")
        assertEquals("https://manifest.googlevideo.com/hls_playlist/itag/233/index.m3u8", LivePlaylist.audioPlaylistUrl(only233))
    }

    @Test
    fun noAudioRenditionNoUrl() {
        assertNull(LivePlaylist.audioPlaylistUrl("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://example.com/v.m3u8"))
    }

    @Test
    fun rewritesSegmentsBySequenceNumber() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:5
            #EXT-X-MEDIA-SEQUENCE:877127
            #EXT-X-DISCONTINUITY-SEQUENCE:229
            #EXTINF:5.0,
            https://rr2.googlevideo.com/videoplayback/sq/877127/file/seg.ts
            #EXTINF:5.0,
            https://rr2.googlevideo.com/videoplayback/sq/877128/file/seg.ts
        """.trimIndent()
        val (out, segments) = LivePlaylist.rewrite(media, BASE) { "x/$it.aac" }
        assertEquals(
            media.replace("https://rr2.googlevideo.com/videoplayback/sq/877127/file/seg.ts", "x/877127.aac")
                .replace("https://rr2.googlevideo.com/videoplayback/sq/877128/file/seg.ts", "x/877128.aac"),
            out,
        )
        assertEquals(
            mapOf(
                877127L to "https://rr2.googlevideo.com/videoplayback/sq/877127/file/seg.ts",
                877128L to "https://rr2.googlevideo.com/videoplayback/sq/877128/file/seg.ts",
            ),
            segments,
        )
    }

    @Test
    fun relativeSegmentsResolveAgainstThePlaylist() {
        val (_, segments) = LivePlaylist.rewrite("#EXTM3U\n#EXTINF:5.0,\nseg/1.ts\n", BASE) { "$it" }
        assertEquals("https://manifest.googlevideo.com/hls_playlist/itag/234/seg/1.ts", segments[0])
    }

    @Test
    fun sequenceStartsAtZeroWithoutTheTag() {
        val (_, segments) = LivePlaylist.rewrite("#EXTM3U\n#EXTINF:5.0,\nhttps://a/1\n", BASE) { "$it" }
        assertEquals(setOf(0L), segments.keys)
    }
}
