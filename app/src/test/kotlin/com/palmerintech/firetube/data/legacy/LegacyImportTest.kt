package com.palmerintech.firetube.data.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixtures in resources/legacy were written by FireTube 1.x's own Video, Playlist and
 * ObjectSerializer classes (compiled from the 1.x sources), so this checks real wire compatibility.
 */
class LegacyImportTest {
    private fun fixture(name: String) = javaClass.getResource("/legacy/$name")!!.readText()

    @Test
    fun readsPlaylistsWritten1x() {
        val playlists = LegacyImport.parsePlaylists(fixture("savedPlaylists.txt"))
        // "Empty one" has no songs and is skipped.
        assertEquals(listOf("Road Trip"), playlists.map { it.first })
        val tracks = playlists.single().second
        // The row with an invalid id is dropped.
        assertEquals(listOf("fJ9rUzIMcZQ", "dQw4w9WgXcQ", "kJQP7kiw5Fk"), tracks.map { it.id })
        assertEquals("Queen - Bohemian Rhapsody", tracks[0].title)
        assertEquals(355L, tracks[0].durationSeconds)
        assertEquals("https://i.ytimg.com/vi/fJ9rUzIMcZQ/hqdefault.jpg", tracks[0].thumbnailUrl)
    }

    @Test
    fun readsRecentWritten1x() {
        val recent = LegacyImport.parseVideos(fixture("recent.txt"))
        assertEquals(listOf("dQw4w9WgXcQ", "fJ9rUzIMcZQ"), recent.map { it.id })
    }

    @Test
    fun garbageIsIgnoredNotThrown() {
        assertTrue(LegacyImport.parsePlaylists(null).isEmpty())
        assertTrue(LegacyImport.parsePlaylists("").isEmpty())
        assertTrue(LegacyImport.parsePlaylists("zzzz").isEmpty())
        assertTrue(LegacyImport.parseVideos("abcd").isEmpty())
        assertNull(LegacyImport.decodeHex("abc"))
    }

    @Test
    fun refusesClassesOutsideTheAllowList() {
        // A serialized java.util.Date encoded the 1.x way must not be deserialized.
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            java.io.ObjectOutputStream(out).use { it.writeObject(arrayListOf(java.util.Date())) }
        }.toByteArray()
        val encoded = buildString { bytes.forEach { b -> append('a' + ((b.toInt() shr 4) and 0xF)); append('a' + (b.toInt() and 0xF)) } }
        assertTrue(LegacyImport.parseVideos(encoded).isEmpty())
    }
}
