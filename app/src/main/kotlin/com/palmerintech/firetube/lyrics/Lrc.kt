package com.palmerintech.firetube.lyrics

/** One timed line of synced lyrics. Empty [text] marks an instrumental gap. */
data class LrcLine(val timeMs: Long, val text: String)

/**
 * Parser for LRC synced lyrics: `[mm:ss.xx]text` lines, possibly with several timestamps on one
 * line (a repeated chorus), an `[offset:±ms]` tag, metadata tags (`[ar:…]`) and enhanced-LRC
 * word timings (`<mm:ss.xx>`), which are dropped.
 */
object Lrc {
    private val timestamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""^\s*\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    /** Written as a code point: a literal one in the source is invisible (and lint rejects it). */
    private val BOM = Char(0xFEFF).toString()
    private val wordTiming = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    /** The timed lines in playback order; empty when [text] has no timestamps at all. */
    fun parse(text: String): List<LrcLine> {
        // A byte order mark would hide the first line's timestamp.
        val lrc = text.removePrefix(BOM)
        // Per the format, a positive offset makes lyrics appear sooner.
        val offset = lrc.lineSequence().firstNotNullOfOrNull { offsetTag.find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
        val lines = mutableListOf<LrcLine>()
        for (raw in lrc.lineSequence()) {
            var rest = raw.trim()
            val times = mutableListOf<Long>()
            // Timestamps are only recognised at the start: "[00:12.00][01:30.00]Chorus".
            while (true) {
                val m = timestamp.matchAt(rest, 0) ?: break
                times += toMillis(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                rest = rest.substring(m.range.last + 1).trimStart()
            }
            if (times.isEmpty()) continue
            val text = rest.replace(wordTiming, "").replace(Regex("\\s+"), " ").trim()
            times.forEach { lines += LrcLine((it - offset).coerceAtLeast(0), text) }
        }
        return lines.sortedBy { it.timeMs } // stable, so same-time lines keep file order
    }

    /** Index of the line playing at [positionMs], or -1 before the first one. */
    fun indexAt(lines: List<LrcLine>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    private fun toMillis(min: String, sec: String, frac: String): Long {
        // ".5" is half a second, ".05" and ".050" are 50 ms.
        val fracMs = when (frac.length) {
            0 -> 0
            1 -> frac.toInt() * 100
            2 -> frac.toInt() * 10
            else -> frac.toInt()
        }
        return (min.toLong() * 60 + sec.toLong()) * 1000 + fracMs
    }
}
