package com.palmerintech.firetube.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcTest {

    private val BOM = Char(0xFEFF).toString()

    @Test
    fun `parses timestamps in their common precisions`() {
        val lines = Lrc.parse(
            """
            [00:01.5]half
            [00:02.25]centis
            [00:03.125]millis
            [00:04]whole
            [01:05:50]colon fraction
            [100:00.00]long
            """.trimIndent(),
        )
        assertEquals(
            listOf(1_500L, 2_250L, 3_125L, 4_000L, 65_500L, 6_000_000L),
            lines.map { it.timeMs },
        )
        assertEquals(listOf("half", "centis", "millis", "whole", "colon fraction", "long"), lines.map { it.text })
    }

    @Test
    fun `a line with several timestamps repeats in order`() {
        val lines = Lrc.parse(
            """
            [00:10.00][00:50.00]Chorus
            [00:20.00]Verse
            [00:30.00] [01:10.00] Bridge
            """.trimIndent(),
        )
        assertEquals(
            listOf(10_000L to "Chorus", 20_000L to "Verse", 30_000L to "Bridge", 50_000L to "Chorus", 70_000L to "Bridge"),
            lines.map { it.timeMs to it.text },
        )
    }

    @Test
    fun `positive offset shows lyrics sooner, negative later`() {
        val sooner = Lrc.parse("[offset:+500]\n[00:10.00]a\n[00:00.20]b")
        assertEquals(listOf(0L, 9_500L), sooner.map { it.timeMs }) // clamped at zero
        val later = Lrc.parse("[offset: -250]\n[00:10.00]a")
        assertEquals(10_250L, later.single().timeMs)
    }

    @Test
    fun `metadata, blank and untimed lines are skipped but empty timed lines are kept as gaps`() {
        val lines = Lrc.parse(
            """
            [ar:Some Artist]
            [ti:Some Song]
            [length: 03:20]

            Not timed at all
            [00:05.00]First
            [00:09.00]
            [00:12.00]  Second   line
            """.trimIndent(),
        )
        assertEquals(listOf("First", "", "Second line"), lines.map { it.text })
    }

    @Test
    fun `enhanced word timings are dropped and CRLF is handled`() {
        val lines = Lrc.parse("[00:01.00]<00:01.00>Hello <00:01.50>world\r\n[00:02.00]Next\r\n")
        assertEquals(listOf("Hello world", "Next"), lines.map { it.text })
    }

    @Test
    fun `a leading byte order mark doesn't hide the first line`() {
        val lines = Lrc.parse(BOM + "[00:01.00]First\n[00:02.00]Second")
        assertEquals(listOf(1_000L to "First", 2_000L to "Second"), lines.map { it.timeMs to it.text })
        assertEquals(500L, Lrc.parse(BOM + "[offset:500]\n[00:01.00]a").single().timeMs)
    }

    @Test
    fun `brackets later in a line are part of the text`() {
        val line = Lrc.parse("[00:01.00]Shout [00:02.00] back").single()
        assertEquals("Shout [00:02.00] back", line.text)
    }

    @Test
    fun `nothing timed gives an empty list`() {
        assertTrue(Lrc.parse("Just\nplain\nwords").isEmpty())
        assertTrue(Lrc.parse("").isEmpty())
    }

    @Test
    fun `indexAt finds the line playing at a position`() {
        val lines = Lrc.parse("[00:05.00]a\n[00:10.00]b\n[00:10.00]b2\n[00:20.00]c")
        assertEquals(-1, Lrc.indexAt(lines, 0))
        assertEquals(-1, Lrc.indexAt(lines, 4_999))
        assertEquals(0, Lrc.indexAt(lines, 5_000))
        assertEquals(0, Lrc.indexAt(lines, 9_999))
        assertEquals(2, Lrc.indexAt(lines, 10_000)) // the later of two lines at the same time
        assertEquals(3, Lrc.indexAt(lines, 999_999))
        assertEquals(-1, Lrc.indexAt(emptyList(), 1_000))
    }
}
