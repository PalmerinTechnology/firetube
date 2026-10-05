package com.palmerintech.firetube.lyrics

/** An artist + song name to look lyrics up by. */
data class LyricsQuery(val artist: String, val title: String)

/**
 * Turns a YouTube title and channel ("Artist - Song (Official Video) [4K]" by "ArtistVEVO") into
 * the likely artist and song name, as a short list of query variants to try in order.
 */
object TitleCleaner {
    /** Words that mark a bracketed part as video packaging rather than part of the song name. */
    private val noise = Regex(
        """\b(official|video|audio|lyrics?|visuali[sz]er|m/?v|hd|hq|4k|8k|1080p|720p|remaster(ed)?|explicit|clean|color coded|music video|full song|eng(lish)? sub)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val bracketed = Regex("""\s*[(\[{【]([^)\]}】]*)[)\]}】]""")
    private val feat = Regex("""\s*[(\[]?\s*\b(ft|feat|featuring)\b\.?\s.*?(?=[)\]]|\s[-–—]\s|$)[)\]]?""", RegexOption.IGNORE_CASE)
    private val trailingNoise = Regex(
        """\s*[-–—|:]?\s*\b(official\s+(music\s+)?(video|audio|lyric video|visuali[sz]er)|(lyric|music)\s+video|lyrics|audio)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val dash = Regex("""\s+[-–—]\s+""")
    private val quotes = Regex("""^["“”'‘’]+|["“”'‘’]+$""")

    /** Up to three distinct variants, most likely first. */
    fun queries(title: String, channel: String): List<LyricsQuery> {
        val artist = cleanArtist(channel)
        val name = cleanTitle(title)
        val out = mutableListOf<LyricsQuery>()
        val parts = name.split(dash, limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            val (left, right) = parts.map { tidy(it) }
            // Usually "Artist - Song", but some channels post "Song - Artist".
            val swapped = artist.isNotEmpty() && sameName(right, artist) && !sameName(left, artist)
            out += if (swapped) LyricsQuery(right, left) else LyricsQuery(left, right)
            out += LyricsQuery(artist, if (swapped) left else right)
        } else {
            out += LyricsQuery(artist, name)
        }
        // "A & B", "A, B", "A x B": the first artist alone often matches when the pair doesn't.
        out.toList().forEach { q -> primaryArtist(q.artist)?.let { out += q.copy(artist = it) } }
        return out.filter { it.artist.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.artist.lowercase() to it.title.lowercase() }
            .take(3)
    }

    /** The song name with video packaging (brackets, "Official Video", featured artists) removed. */
    fun cleanTitle(title: String): String {
        var t = title
        // Featured artists first: "(feat. X)" is otherwise a bracket without noise words and stays.
        t = feat.replace(t, "")
        t = bracketed.replace(t) { m -> if (noise.containsMatchIn(m.groupValues[1]) || m.groupValues[1].isBlank()) "" else m.value }
        // "Song | Some Channel Promo" — what follows a bar is never part of the name.
        t = t.substringBefore(" | ").substringBefore(" // ")
        while (true) {
            val next = trailingNoise.replace(t, "")
            if (next == t) break
            t = next
        }
        return tidy(t)
    }

    /** A channel name as an artist: "Adele - Topic" / "AdeleVEVO" / "Adele Official" → "Adele". */
    fun cleanArtist(channel: String): String {
        var a = channel.replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")
        if (a.endsWith("VEVO") && a.length > 4) {
            a = a.removeSuffix("VEVO")
            // "TaylorSwiftVEVO": VEVO channel names drop the spaces.
            if (' ' !in a.trim()) a = a.replace(Regex("""(?<=\p{Ll})(?=\p{Lu})"""), " ")
        }
        a = a.replace(Regex("""\s+(official(\s+(channel|artist|music))?|music)$""", RegexOption.IGNORE_CASE), "")
        return tidy(feat.replace(a, ""))
    }

    private fun primaryArtist(artist: String): String? {
        val first = artist.split(Regex("""\s*(,|&|\band\b|\bx\b|\bvs\.?|\bwith\b)\s*""", RegexOption.IGNORE_CASE), limit = 2)
        return first.takeIf { it.size == 2 }?.get(0)?.let(::tidy)?.takeIf { it.isNotEmpty() }
    }

    private fun tidy(s: String) = s.replace(Regex("""\s+"""), " ").trim().trim('-', '–', '—', '|', ':', ' ')
        .replace(quotes, "").trim()

    private fun sameName(a: String, b: String) = normalize(a).let { it.isNotEmpty() && it == normalize(b) }

    /** Lowercase letters and digits only, for loose comparisons. */
    internal fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
