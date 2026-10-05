package com.palmerintech.firetube.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleCleanerTest {

    private fun first(title: String, channel: String) = TitleCleaner.queries(title, channel).first()

    @Test
    fun `strips video packaging from titles`() {
        mapOf(
            "Song (Official Video)" to "Song",
            "Song [Official Music Video] [4K]" to "Song",
            "Song (Lyrics)" to "Song",
            "Song [Lyric Video]" to "Song",
            "Song (Official Audio) (Remastered 2009)" to "Song",
            "Song (Visualizer)" to "Song",
            "Song Official Music Video" to "Song",
            "Song - Official Video" to "Song",
            "Song | Official Audio" to "Song",
            "Song (HD)" to "Song",
            "Song 【MV】" to "Song",
            "\"Song\"" to "Song",
            "Song (Acoustic)" to "Song (Acoustic)", // a real version name stays
            "Song (Remix) [Official Video]" to "Song (Remix)",
        ).forEach { (raw, clean) -> assertEquals(raw, clean, TitleCleaner.cleanTitle(raw)) }
    }

    @Test
    fun `strips featured artists in any spelling`() {
        mapOf(
            "Song (feat. Other)" to "Song",
            "Song [ft. Other & Another]" to "Song",
            "Song ft. Other" to "Song",
            "Song featuring Other (Official Video)" to "Song",
            "Song Feat Other" to "Song",
        ).forEach { (raw, clean) -> assertEquals(raw, clean, TitleCleaner.cleanTitle(raw)) }
        // Not a "ft" word: left alone.
        assertEquals("Left Behind", TitleCleaner.cleanTitle("Left Behind"))
        assertEquals("Defeat", TitleCleaner.cleanTitle("Defeat"))
    }

    @Test
    fun `channel names become artist names`() {
        assertEquals("Adele", TitleCleaner.cleanArtist("Adele - Topic"))
        assertEquals("Taylor Swift", TitleCleaner.cleanArtist("TaylorSwiftVEVO"))
        assertEquals("Muse", TitleCleaner.cleanArtist("MuseVEVO"))
        assertEquals("Imagine Dragons", TitleCleaner.cleanArtist("Imagine Dragons Official"))
        assertEquals("Coldplay", TitleCleaner.cleanArtist("Coldplay"))
        assertEquals("VEVO", TitleCleaner.cleanArtist("VEVO"))
    }

    @Test
    fun `splits Artist - Song`() {
        assertEquals(LyricsQuery("Rick Astley", "Never Gonna Give You Up"), first("Rick Astley - Never Gonna Give You Up (Official Music Video)", "Rick Astley"))
        assertEquals(LyricsQuery("Daft Punk", "One More Time"), first("Daft Punk – One More Time [Official Video]", "Daft Punk"))
        assertEquals(LyricsQuery("Artist", "Song"), first("Artist ft. Other - Song", "Some Channel"))
        assertEquals(LyricsQuery("Artist", "Song"), first("Artist - Song (feat. Other) [Lyrics]", "Lyrics Hub"))
        // Hyphens inside names aren't separators.
        assertEquals(LyricsQuery("Jay-Z", "Song"), first("Jay-Z - Song", "JayZVEVO"))
    }

    @Test
    fun `Song - Artist is swapped when the channel says so`() {
        assertEquals(LyricsQuery("Adele", "Hello"), first("Hello - Adele", "AdeleVEVO"))
    }

    @Test
    fun `uses the channel as artist when the title has no dash`() {
        assertEquals(listOf(LyricsQuery("Adele", "Hello")), TitleCleaner.queries("Hello", "Adele - Topic"))
    }

    @Test
    fun `offers the channel and the first of several artists as fallbacks`() {
        assertEquals(
            listOf(
                LyricsQuery("Simon & Garfunkel", "The Boxer"),
                LyricsQuery("Simon & Garfunkel Hits", "The Boxer"),
                LyricsQuery("Simon", "The Boxer"),
            ),
            TitleCleaner.queries("Simon & Garfunkel - The Boxer (Audio)", "Simon & Garfunkel Hits"),
        )
        // The same artist twice is only tried once.
        assertEquals(
            listOf(LyricsQuery("Muse", "Uprising")),
            TitleCleaner.queries("Muse - Uprising [Official Music Video]", "Muse"),
        )
    }

    @Test
    fun `never offers a query with a blank side`() {
        assertEquals(emptyList<LyricsQuery>(), TitleCleaner.queries("(Official Video)", ""))
        assertEquals(listOf(LyricsQuery("Band", "Song")), TitleCleaner.queries("Band - Song", ""))
    }
}
