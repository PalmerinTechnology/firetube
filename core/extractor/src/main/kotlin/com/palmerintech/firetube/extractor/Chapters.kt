package com.palmerintech.firetube.extractor

import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamSegment

/**
 * Chapters of long videos: YouTube's own (NewPipe's stream segments) when it has them, otherwise
 * a timestamped track list in the description ("0:00 Intro", "12:34 - Song", "1:02:03 Track").
 */
object Chapters {
    /** A timestamp at the start of a line, optionally bracketed, then an optional separator and the title. */
    private val line = Regex("""^[\[(]?((?:\d{1,2}:)?\d{1,2}:\d{2})[\])]?\s*(?:[-–—:|•]\s*)?(.*)$""")
    private val br = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val tag = Regex("<[^>]+>")

    /** At least this many description timestamps, so a lone "at 2:30 she says..." isn't a track list. */
    private const val MIN_FROM_DESCRIPTION = 3

    /**
     * NewPipe's segments as chapters; empty unless there are at least two, which is all a chapter
     * list needs. An untitled one keeps an empty title, for the app to name in the user's language.
     */
    internal fun fromSegments(segments: List<StreamSegment>, durationSeconds: Long = 0): List<Chapter> {
        val chapters = segments
            .filter { it.startTimeSeconds >= 0 && (durationSeconds <= 0 || it.startTimeSeconds < durationSeconds) }
            .sortedBy { it.startTimeSeconds }
            .distinctBy { it.startTimeSeconds }
            .map { Chapter(it.title?.trim().orEmpty(), it.startTimeSeconds * 1000L) }
        return chapters.takeIf { it.size >= 2 }.orEmpty()
    }

    /**
     * Chapters from a track list in [text]. Like YouTube's own rule, the list must start at 0:00
     * and go up, with at least three entries; it ends at the first timestamp that doesn't (or
     * that is past [durationSeconds], when known), so a second list further down can't mix in.
     */
    fun fromDescription(text: String, durationSeconds: Long = 0): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        for (raw in text.lineSequence()) {
            val m = line.matchEntire(raw.trim()) ?: continue
            val title = m.groupValues[2].trim()
            if (title.isEmpty()) continue
            val startMs = toMillis(m.groupValues[1]) ?: continue
            if (chapters.isEmpty()) {
                if (startMs == 0L) chapters += Chapter(title, 0)
                continue
            }
            if (startMs <= chapters.last().startMs || (durationSeconds > 0 && startMs >= durationSeconds * 1000)) break
            chapters += Chapter(title, startMs)
        }
        return chapters.takeIf { it.size >= MIN_FROM_DESCRIPTION }.orEmpty()
    }

    /** The description as plain text: YouTube's comes as HTML, with `<br>` for line breaks and links around timestamps. */
    internal fun plainText(description: Description?): String {
        val content = description?.content.orEmpty()
        if (description?.type != Description.HTML) return content
        return content.replace(br, "\n").replace(tag, "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
    }

    /** Index of the chapter playing at [positionMs], or -1 before the first one (or when there are none). */
    fun indexAt(chapters: List<Chapter>, positionMs: Long): Int {
        var lo = 0
        var hi = chapters.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (chapters[mid].startMs <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    /** "1:02:03" / "12:34" / "0:00" to millis; null when minutes or seconds are out of range. */
    private fun toMillis(stamp: String): Long? {
        val parts = stamp.split(':').map { it.toLong() }
        val (h, m, s) = if (parts.size == 3) parts else listOf(0L) + parts
        if (s >= 60 || (parts.size == 3 && m >= 60)) return null
        return ((h * 60 + m) * 60 + s) * 1000
    }
}
