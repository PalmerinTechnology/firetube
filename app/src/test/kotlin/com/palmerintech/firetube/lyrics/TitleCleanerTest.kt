package com.palmerintech.firetube.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `Topic uploads are never split on a dash`() {
        assertEquals(
            listOf(LyricsQuery("The Beatles", "Here Comes The Sun")),
            TitleCleaner.queries("Here Comes The Sun - Remastered 2009", "The Beatles - Topic"),
        )
        assertEquals(listOf(LyricsQuery("Jeff Buckley", "Hallelujah")), TitleCleaner.queries("Hallelujah - Live", "Jeff Buckley - Topic"))
        // A dash that's part of the song name stays.
        assertEquals(
            listOf(LyricsQuery("Some Band", "Me - Myself and I")),
            TitleCleaner.queries("Me - Myself and I", "Some Band - Topic"),
        )
    }

    @Test
    fun `version labels after a dash are stripped, never taken as the song`() {
        mapOf(
            "Here Comes The Sun - Remastered 2009" to "Here Comes The Sun",
            "Song - 2011 Remaster" to "Song",
            "Song - Live" to "Song",
            "Song - Live at Wembley Stadium" to "Song",
            "Song - Mono Version" to "Song",
            "Song - Radio Edit" to "Song",
            "Song - Single Version - Remastered" to "Song",
            "Song - Demo" to "Song",
            "Let It Go - From \"Frozen\"" to "Let It Go",
            "Song - Acoustic" to "Song (Acoustic)", // a different recording keeps its name
            "Song - Remix" to "Song (Remix)",
            "Song (Live)" to "Song",
            "Song (2015 Remaster)" to "Song",
        ).forEach { (raw, clean) -> assertEquals(raw, clean, TitleCleaner.cleanTitle(raw)) }
        // Song names that only look like labels.
        assertEquals("Live", TitleCleaner.cleanTitle("Live"))
        assertEquals("Prince - 1999", TitleCleaner.cleanTitle("Prince - 1999"))
        assertEquals("Portugal. The Man - Live in the Moment", TitleCleaner.cleanTitle("Portugal. The Man - Live in the Moment"))
        assertEquals("The Beatles - From Me to You", TitleCleaner.cleanTitle("The Beatles - From Me to You"))
        // Non-Topic: the label goes, then Artist - Song splits as usual.
        assertEquals(LyricsQuery("The Beatles", "Here Comes The Sun"), first("The Beatles - Here Comes The Sun - Remastered 2009", "Some Uploader"))
        assertEquals(LyricsQuery("Jeff Buckley", "Hallelujah"), first("Hallelujah - Live", "Jeff Buckley"))
    }

    @Test
    fun `song names match exactly, or word for word when long enough`() {
        assertTrue(TitleCleaner.sameSong("Here Comes The Sun", "Here Comes The Sun - Remastered 2009"))
        assertTrue(TitleCleaner.sameSong("Hallelujah", "Hallelujah (Live)"))
        assertTrue(TitleCleaner.sameSong("Never Gonna Give You Up", "Never Gonna Give You Up (2022 Rework) Extra"))
        assertTrue(TitleCleaner.sameSong("Beyoncé", "BEYONCE"))
        // A single word only matches itself: "Live" isn't "Live Forever", and never "Alive".
        assertFalse(TitleCleaner.sameSong("Live", "Live Forever"))
        assertFalse(TitleCleaner.sameSong("Live", "Alive"))
        assertFalse(TitleCleaner.sameSong("Hello", "Hello Again"))
        // Too short to trust by containment, and not inside a word.
        assertFalse(TitleCleaner.sameSong("I U", "I U Me"))
        assertFalse(TitleCleaner.sameSong("Come Here", "Welcome Here"))
        // Empty names never match anything.
        assertFalse(TitleCleaner.sameSong("", "Song"))
        assertFalse(TitleCleaner.sameSong("(Official Video)", "Song"))
        assertFalse(TitleCleaner.sameSong("Song", ""))
    }

    @Test
    fun `one-word names match through brackets the cleaner doesn't know`() {
        assertTrue(TitleCleaner.sameSong("Despacito", "Despacito (Letra)"))
        assertTrue(TitleCleaner.sameSong("HUMBLE.", "HUMBLE. (Prod. Mike WiLL)"))
        assertTrue(TitleCleaner.sameSong("Umbrella", "Umbrella (Orange Version)"))
        assertTrue(TitleCleaner.sameSong("Umbrella [Orange Version]", "Umbrella"))
        // Still only the same name: other words outside the brackets don't match.
        assertFalse(TitleCleaner.sameSong("Umbrella", "Umbrella Song (Orange Version)"))
        assertFalse(TitleCleaner.sameSong("Live", "Live Forever (Remastered)"))
        // Without its brackets this name is empty, which never matches by that rule.
        assertFalse(TitleCleaner.sameSong("(Orange Version)", "Umbrella"))
    }

    @Test
    fun `a different recording is a different song`() {
        listOf(
            "Song (Remix)" to "Song",
            "Song (Acoustic)" to "Song",
            "Song (Instrumental)" to "Song",
            "Song (Karaoke Version)" to "Song",
            "Song (Sped Up)" to "Song",
            "Song (Slowed + Reverb)" to "Song",
            "Song (Club Mix)" to "Song",
            "Song (Reprise)" to "Song",
            "Bad (Part 2)" to "Bad",
            "Bad (Pt. 2)" to "Bad",
            "Intro (Interlude)" to "Intro",
            "Song (Remix)" to "Song (Acoustic)",
            "Song (Instrumental)" to "Song (Karaoke Version)",
            // Containment doesn't let them through either.
            "Here Comes The Sun (Instrumental)" to "Here Comes The Sun",
            "Here Comes The Sun - Acoustic" to "Here Comes The Sun",
        ).forEach { (a, b) ->
            assertFalse("$a ~ $b", TitleCleaner.sameSong(a, b))
            assertFalse("$b ~ $a", TitleCleaner.sameSong(b, a))
        }
        // The same version still matches itself, whichever way it's written.
        assertTrue(TitleCleaner.sameSong("Song (Remix)", "Song - Remix"))
        assertTrue(TitleCleaner.sameSong("Bad (Part 2)", "Bad [Part 2]"))
        // Versions with the same words still match: "(Live)" and "(Radio Edit)" are cleaned away.
        assertTrue(TitleCleaner.sameSong("Song (Radio Edit)", "Song"))
        assertTrue(TitleCleaner.sameSong("Song (Live)", "Song"))
    }

    @Test
    fun `titles that say they're instrumental`() {
        assertTrue(TitleCleaner.saysInstrumental("Song (Instrumental)"))
        assertTrue(TitleCleaner.saysInstrumental("Song - Karaoke Version"))
        assertFalse(TitleCleaner.saysInstrumental("Song (Official Video)"))
    }

    @Test
    fun `prod and letra brackets are packaging`() {
        assertEquals("Despacito", TitleCleaner.cleanTitle("Despacito (Letra)"))
        assertEquals("HUMBLE.", TitleCleaner.cleanTitle("HUMBLE. (Prod. Mike WiLL)"))
        assertEquals(LyricsQuery("Kendrick Lamar", "HUMBLE."), first("Kendrick Lamar - HUMBLE. (Prod. Mike WiLL) [Official Video]", "KendrickLamarVEVO"))
    }

    @Test
    fun `artists match loosely but not by chance`() {
        assertTrue(TitleCleaner.sameArtist("Adele", "Adele, Adele"))
        assertTrue(TitleCleaner.sameArtist("Simon", "Simon & Garfunkel"))
        assertTrue(TitleCleaner.sameArtist("Beatles", "The Beatles"))
        assertTrue(TitleCleaner.sameArtist("Jay Z", "JAY-Z"))
        assertTrue(TitleCleaner.sameArtist("Beyonce", "Beyoncé"))
        assertTrue(TitleCleaner.sameArtist("U2", "U2"))
        assertFalse(TitleCleaner.sameArtist("U2", "U2K Band")) // short names must be equal
        assertFalse(TitleCleaner.sameArtist("Oasis", "Jeff Buckley"))
        assertFalse(TitleCleaner.sameArtist("Lyrics Hub", "Jeff Buckley"))
        assertFalse(TitleCleaner.sameArtist("", "Adele"))
        assertFalse(TitleCleaner.sameArtist("Adele", ""))
    }

    @Test
    fun `label channels take the artist from a quoted title`() {
        assertEquals(
            listOf(LyricsQuery("BTS", "Dynamite")),
            TitleCleaner.queries("BTS (방탄소년단) 'Dynamite' Official MV", "HYBE LABELS"),
        )
        assertEquals(LyricsQuery("IU", "Palette"), first("아이유 (IU)「Palette」(Official Video)", "Kakao Entertainment"))
        assertEquals(LyricsQuery("YOASOBI", "アイドル"), first("YOASOBI「アイドル」Official Music Video", "Sony Music Japan"))
        assertEquals(LyricsQuery("BLACKPINK", "How You Like That"), first("BLACKPINK - 'How You Like That' M/V", "BLACKPINK"))
        assertEquals(LyricsQuery("Taylor Swift", "Anti-Hero"), first("Taylor Swift “Anti-Hero” (Official Music Video)", "Republic Records"))
    }

    @Test
    fun `lyric channels try both sides of the dash, never their own name`() {
        assertEquals(
            listOf(LyricsQuery("Shawn Mendes", "Treat You Better"), LyricsQuery("Treat You Better", "Shawn Mendes")),
            TitleCleaner.queries("Shawn Mendes - Treat You Better (Lyrics)", "7clouds"),
        )
        assertEquals(LyricsQuery("Rema", "Calm Down"), first("Rema - Calm Down (Lyrics)", "Vibe Music"))
        // "Song - Artist | Lyrics": the second variant has it the right way round.
        assertEquals(
            LyricsQuery("Billie Eilish", "Birds of a Feather"),
            TitleCleaner.queries("Birds of a Feather - Billie Eilish | Lyrics", "Dan Music Lyrics")[1],
        )
        assertEquals(LyricsQuery("BTS", "Butter"), first("BTS (방탄소년단) - Butter", "HYBE LABELS"))
    }

    @Test
    fun `quotes and parentheses that aren't a song or a second script stay`() {
        // Not quoting the song: an apostrophe in a name, or more title after the quote.
        assertEquals(LyricsQuery("Guns N' Roses", "Sweet Child O' Mine"), first("Guns N' Roses - Sweet Child O' Mine (Official Music Video)", "GunsNRosesVEVO"))
        assertEquals(LyricsQuery("Oasis", "Rock 'n' Roll Star"), first("Rock 'n' Roll Star", "Oasis"))
        // A dash before the quote: the dash split wins.
        assertEquals("Queen", first("Queen - Song \"Live\"", "Queen").artist)
        // Two Latin names: not a transliteration.
        assertEquals(LyricsQuery("Prince (The Artist)", "Song"), first("Prince (The Artist) - Song", "Some Uploader"))
        // A Topic upload is just the song name.
        assertEquals(listOf(LyricsQuery("Oasis", "Don't Look Back 'In' Anger")), TitleCleaner.queries("Don't Look Back 'In' Anger", "Oasis - Topic"))
    }

    @Test
    fun `never offers a query with a blank side`() {
        assertEquals(emptyList<LyricsQuery>(), TitleCleaner.queries("(Official Video)", ""))
        assertEquals(listOf(LyricsQuery("Band", "Song")), TitleCleaner.queries("Band - Song", ""))
    }
}
