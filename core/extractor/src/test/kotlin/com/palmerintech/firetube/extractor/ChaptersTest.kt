package com.palmerintech.firetube.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamSegment

class ChaptersTest {

    private fun starts(chapters: List<Chapter>) = chapters.map { it.startMs }

    @Test
    fun descriptionTimestampsInTheirCommonShapes() {
        val chapters = Chapters.fromDescription(
            """
            Tracklist:
            0:00 Intro
            4:05 - Second Song
            12:34 – Third (feat. Someone)
            [15:00] Bracketed
            (20:10) Parenthesised
            59:59 | Piped
            1:02:03 Past the hour
            01:10:00: Zero padded
            """.trimIndent(),
        )
        assertEquals(
            listOf(0L, 245_000L, 754_000L, 900_000L, 1_210_000L, 3_599_000L, 3_723_000L, 4_200_000L),
            starts(chapters),
        )
        assertEquals(
            listOf("Intro", "Second Song", "Third (feat. Someone)", "Bracketed", "Parenthesised", "Piped", "Past the hour", "Zero padded"),
            chapters.map { it.title },
        )
    }

    @Test
    fun otherLinesInBetweenAreIgnored() {
        val chapters = Chapters.fromDescription(
            """
            Recorded live in Berlin. Follow me on Instagram!

            0:00 Opening

            3:00 Track Two
            Some note about track two
            6:30 Track Three
            """.trimIndent(),
        )
        assertEquals(listOf(0L, 180_000L, 390_000L), starts(chapters))
    }

    @Test
    fun needsThreeTimestamps() {
        assertTrue(Chapters.fromDescription("0:00 Intro\n3:00 Outro").isEmpty())
    }

    @Test
    fun mustStartAtZero() {
        // A track list with durations, or comments pointing at moments, isn't a chapter list.
        assertTrue(Chapters.fromDescription("1:30 Best part\n2:45 Drop\n4:00 Ending").isEmpty())
        assertTrue(Chapters.fromDescription("Great video\nat 2:30 she laughs, 3:10 he falls, 5:00 the end").isEmpty())
    }

    @Test
    fun durationListsAreRejected() {
        // Song lengths in a track listing: starts with 0:00 nowhere, and doesn't go up.
        assertTrue(Chapters.fromDescription("3:45 Song One\n4:12 Song Two\n2:58 Song Three\n5:01 Song Four").isEmpty())
    }

    @Test
    fun stopsAtTheFirstOutOfOrderTimestamp() {
        val chapters = Chapters.fromDescription(
            """
            0:00 One
            2:00 Two
            4:00 Three
            Bonus clip timestamps:
            0:00 Clip intro
            1:00 Clip end
            """.trimIndent(),
        )
        assertEquals(listOf("One", "Two", "Three"), chapters.map { it.title })
    }

    @Test
    fun rejectsTooFewWhenTheListBreaksEarly() {
        assertTrue(Chapters.fromDescription("0:00 One\n2:00 Two\n1:00 Back again\n3:00 Three").isEmpty())
    }

    @Test
    fun stopsPastTheDuration() {
        val chapters = Chapters.fromDescription("0:00 A\n1:00 B\n2:00 C\n9:00 D", durationSeconds = 300)
        assertEquals(listOf("A", "B", "C"), chapters.map { it.title })
    }

    @Test
    fun bareTimestampsAndBadOnesAreSkipped() {
        val chapters = Chapters.fromDescription("0:00 Intro\n1:00\n1:75 Not a time\n2:00 Two\n3:00 Three")
        assertEquals(listOf(0L, 120_000L, 180_000L), starts(chapters))
    }

    @Test
    fun youTubeHtmlDescriptionsBecomePlainLines() {
        val html = """Live set &amp; more<br><a href="https://www.youtube.com/watch?v=abc&amp;t=0s">0:00</a> Intro<br>""" +
            """<a href="https://www.youtube.com/watch?v=abc&amp;t=95s">1:35</a> Rock &amp; Roll<br/>""" +
            """<a href="https://www.youtube.com/watch?v=abc&amp;t=200s">3:20</a> It&#39;s &quot;over&quot;"""
        val text = Chapters.plainText(Description(html, Description.HTML))
        val chapters = Chapters.fromDescription(text)
        assertEquals(listOf("Intro", "Rock & Roll", "It's \"over\""), chapters.map { it.title })
        assertEquals(listOf(0L, 95_000L, 200_000L), starts(chapters))
        assertEquals("plain <b>text</b>", Chapters.plainText(Description("plain <b>text</b>", Description.PLAIN_TEXT)))
        assertEquals("", Chapters.plainText(null))
    }

    @Test
    fun segmentsMapToChaptersInOrder() {
        val chapters = Chapters.fromSegments(
            listOf(StreamSegment("Second", 300), StreamSegment(" First ", 0), StreamSegment("Dup", 300), StreamSegment("", 600)),
        )
        assertEquals(listOf(Chapter("First", 0), Chapter("Second", 300_000), Chapter("Chapter 3", 600_000)), chapters)
    }

    @Test
    fun segmentsPastTheDurationOrAloneAreDropped() {
        assertEquals(
            listOf(0L, 60_000L),
            starts(Chapters.fromSegments(listOf(StreamSegment("A", 0), StreamSegment("B", 60), StreamSegment("C", 900)), durationSeconds = 600)),
        )
        assertTrue(Chapters.fromSegments(listOf(StreamSegment("Only", 0))).isEmpty())
        assertTrue(Chapters.fromSegments(emptyList()).isEmpty())
    }

    @Test
    fun indexAtFindsThePlayingChapter() {
        val chapters = listOf(Chapter("A", 0), Chapter("B", 60_000), Chapter("C", 120_000), Chapter("D", 300_000))
        assertEquals(0, Chapters.indexAt(chapters, 0))
        assertEquals(0, Chapters.indexAt(chapters, 59_999))
        assertEquals(1, Chapters.indexAt(chapters, 60_000))
        assertEquals(2, Chapters.indexAt(chapters, 299_999))
        assertEquals(3, Chapters.indexAt(chapters, 300_000))
        assertEquals(3, Chapters.indexAt(chapters, 10_000_000))
    }

    @Test
    fun indexAtBeforeTheFirstOrWithNone() {
        assertEquals(-1, Chapters.indexAt(listOf(Chapter("A", 5_000), Chapter("B", 9_000)), 4_999))
        assertEquals(-1, Chapters.indexAt(emptyList(), 1_000))
    }
}
