package com.palmerintech.firetube.lyrics

import java.text.Normalizer

/** An artist + song name to look lyrics up by. */
data class LyricsQuery(val artist: String, val title: String)

/**
 * Turns a YouTube title and channel ("Artist - Song (Official Video) [4K]" by "ArtistVEVO") into
 * the likely artist and song name, as a short list of query variants to try in order.
 */
object TitleCleaner {
    /** Words that mark a bracketed part as video packaging rather than part of the song name. */
    private val noise = Regex(
        """\b(official|video|audio|lyrics?|visuali[sz]er|m/?v|hd|hq|4k|8k|1080p|720p|remaster(ed)?|explicit|clean|color coded|music video|full song|eng(lish)? sub|prod|letra)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val bracketed = Regex("""\s*[(\[{【]([^)\]}】]*)[)\]}】]""")
    private val feat = Regex("""\s*[(\[]?\s*\b(ft|feat|featuring)\b\.?\s.*?(?=[)\]]|\s[-–—]\s|$)[)\]]?""", RegexOption.IGNORE_CASE)
    private val trailingNoise = Regex(
        """\s*[-–—|:]?\s*\b(official\s+(music\s+)?(video|audio|lyric video|visuali[sz]er|m/?v)|(lyric|music)\s+video|lyrics|audio|m/?v)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val dash = Regex("""\s+[-–—]\s+""")
    /**
     * A quoted song name after the artist: "BTS 'Dynamite'", "IU「Palette」". A Latin quote must
     * follow a space and open on a non-space, so "Guns N' Roses" and "Rock 'n' Roll" don't count.
     */
    private val quoted = Regex("""^(.+?)(?:\s+['‘"“](\S.*?)['’"”]|\s*[「『](.+?)[」』])(.*)$""")
    /** "BTS (방탄소년단)": one name in two scripts. */
    private val twoScripts = Regex("""^(.+?)\s*[(（]([^()（）]+)[)）]$""")
    /**
     * Channels that post other artists' songs: labels ("HYBE LABELS", "Atlantic Records") and
     * lyric channels ("7clouds", "Vibe Music", "Lyrics Hub"). Their name is never the artist.
     */
    private val labelChannel = Regex(
        """\b(labels?|records|recordings|music|lyrics?|vibes?|entertainment)\b|7clouds""",
        RegexOption.IGNORE_CASE,
    )
    private val quotes = Regex("""^["“”'‘’]+|["“”'‘’]+$""")
    private val topic = Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE)
    private val marks = Regex("""\p{Mn}+""")
    private val year = Regex("""(19|20)\d\d""")

    /**
     * Words a version label is made of ("Remastered 2009", "Live", "Mono", "Radio Edit"). Such a
     * label names a recording, not the song, so it's never taken as the song name.
     */
    private val versionWords = setOf(
        "remaster", "remastered", "remasters", "digital", "digitally", "live", "demo", "mono", "stereo",
        "radio", "single", "album", "edit", "version", "mix", "original", "extended", "deluxe", "edition",
        "bonus", "track", "alternate", "alternative", "take", "session", "sessions", "explicit", "clean",
        "acoustic", "remix", "remixed", "unplugged",
    )
    /** Version words for a different-sounding recording, kept in the name as "Song (Acoustic)". */
    private val keptVersionWords = setOf("acoustic", "remix", "remixed", "unplugged")
    // Narrow on purpose: "Live in the Moment" and "From Me to You" are songs.
    private val liveAt = Regex("""^live\s+(at|from)\s.*""", RegexOption.IGNORE_CASE)
    private val fromSoundtrack = Regex(
        """^from\s+(["“‘'].*|the\s.*\b(soundtrack|motion picture|film|movie|series|musical)\b.*)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Words that make a differently-worded (or wordless) recording of a song: a name with one of
     * them never matches the name without it.
     */
    private val distinguishingWords = setOf(
        "acoustic", "remix", "remixed", "rmx", "unplugged", "instrumental", "karaoke", "part", "pt",
        "reprise", "interlude", "intro", "outro", "sped", "slowed", "mix", "edit",
    )

    /** Shortest name (in letters) that may match by being contained in a longer one. */
    private const val MIN_CONTAINED = 4

    /** Up to three distinct variants, most likely first. */
    fun queries(title: String, channel: String): List<LyricsQuery> {
        val artist = cleanArtist(channel)
        val name = cleanTitle(title)
        val out = mutableListOf<LyricsQuery>()
        val parts = name.split(dash, limit = 2)
        // YouTube Music's auto-generated "Artist - Topic" uploads are titled with just the song
        // name, so a dash or quote in them is part of it.
        val isTopic = topic.containsMatchIn(channel.trim())
        // A label's or lyric channel's name isn't the artist: only the title says who it is.
        val isLabel = !isTopic && labelChannel.containsMatchIn(channel)
        val quotedSong = if (isTopic) null else quotedSong(title)
        if (quotedSong != null) {
            out += quotedSong
            // On an artist's own channel the quotes might be part of the name after all.
            if (!isLabel) out += listOf(LyricsQuery(artist, quotedSong.title), LyricsQuery(artist, name))
        } else if (!isTopic && parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            val (left, right) = parts.map { tidy(it) }
            if (isLabel) {
                // Usually "Artist - Song", but some lyric channels post "Song - Artist".
                out += LyricsQuery(titleArtist(left), right)
                out += LyricsQuery(titleArtist(right), left)
            } else {
                // The same, told apart by the channel.
                val swapped = artist.isNotEmpty() && sameName(right, artist) && !sameName(left, artist)
                out += if (swapped) LyricsQuery(right, left) else LyricsQuery(titleArtist(left), right)
                out += LyricsQuery(artist, if (swapped) left else right)
            }
        } else {
            out += LyricsQuery(artist, name)
        }
        // "A & B", "A, B", "A x B": the first artist alone often matches when the pair doesn't.
        out.toList().forEach { q -> primaryArtist(q.artist)?.let { out += q.copy(artist = it) } }
        return out.filter { it.artist.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.artist.lowercase() to it.title.lowercase() }
            .take(3)
    }

    /**
     * The song name with video packaging (brackets, "Official Video", featured artists) and
     * version labels ("- Remastered 2009", "(Live)") removed.
     */
    fun cleanTitle(title: String): String {
        var t = title
        // Featured artists first: "(feat. X)" is otherwise a bracket without noise words and stays.
        t = feat.replace(t, "")
        t = bracketed.replace(t) { m ->
            val inside = m.groupValues[1]
            val drop = inside.isBlank() || noise.containsMatchIn(inside) || (isVersion(inside) && !keepsVersion(inside))
            if (drop) "" else m.value
        }
        // "Song | Some Channel Promo" — what follows a bar is never part of the name.
        t = t.substringBefore(" | ").substringBefore(" // ")
        while (true) {
            val next = trailingNoise.replace(t, "")
            if (next == t) break
            t = next
        }
        // "Song - Remastered 2009" / "Song - Live": a version label after a dash. Only ever as a
        // suffix, so a song actually called "Live" keeps its name.
        while (true) {
            val sep = dash.findAll(t).lastOrNull() ?: break
            val label = tidy(t.substring(sep.range.last + 1))
            val head = t.substring(0, sep.range.first)
            if (head.isBlank() || !isVersion(label)) break
            t = if (keepsVersion(label)) "$head ($label)" else head
        }
        return tidy(t)
    }

    /** A channel name as an artist: "Adele - Topic" / "AdeleVEVO" / "Adele Official" → "Adele". */
    fun cleanArtist(channel: String): String {
        var a = channel.replace(topic, "")
        if (a.endsWith("VEVO") && a.length > 4) {
            a = a.removeSuffix("VEVO")
            // "TaylorSwiftVEVO": VEVO channel names drop the spaces.
            if (' ' !in a.trim()) a = a.replace(Regex("""(?<=\p{Ll})(?=\p{Lu})"""), " ")
        }
        a = a.replace(Regex("""\s+(official(\s+(channel|artist|music))?|music)$""", RegexOption.IGNORE_CASE), "")
        return tidy(feat.replace(a, ""))
    }

    /**
     * Whether two song names are the same song: equal once cleaned (also after dropping brackets
     * that don't change the recording), or one contained word for word in the other. Containment
     * is only trusted for names of two or more words, so "Live" matches neither "Live Forever"
     * nor "Alive". A remix, acoustic take, instrumental, "Part 2" and so on is a different song
     * here, since its lyrics (or lack of them) differ. Empty names never match.
     */
    fun sameSong(a: String, b: String): Boolean {
        val x = words(cleanTitle(a))
        val y = words(cleanTitle(b))
        if (x.isEmpty() || y.isEmpty()) return false
        if (x.joinToString("") == y.joinToString("")) return true
        // Brackets this cleaner doesn't recognise ("Umbrella (Orange Version)"): the same name
        // without them still counts, even for one word, unless they name a different recording.
        val bareX = normalize(dropIncidentalBrackets(cleanTitle(a)))
        if (bareX.isNotEmpty() && bareX == normalize(dropIncidentalBrackets(cleanTitle(b)))) return true
        val (short, long) = if (x.size <= y.size) x to y else y to x
        if (short.size < 2 || short.joinToString("").length < MIN_CONTAINED) return false
        // "Here Comes The Sun" isn't "Here Comes The Sun (Instrumental)".
        if (long.any { it in distinguishingWords && it !in short }) return false
        return long.windowed(short.size).any { it == short }
    }

    /** Whether a title says it's a recording without vocals ("Song (Instrumental)", "Karaoke"). */
    fun saysInstrumental(title: String) = words(title).any { it == "instrumental" || it == "karaoke" }

    private fun dropIncidentalBrackets(title: String) =
        bracketed.replace(title) { m -> if (words(m.groupValues[1]).any { it in distinguishingWords }) m.value else "" }

    /**
     * Whether a result's artist is the queried one. LRCLIB lists collaborations as one string
     * ("Simon & Garfunkel", "Adele, Adele"), so containment either way counts, except for very
     * short names, which must be equal. Empty names never match.
     */
    fun sameArtist(query: String, result: String): Boolean {
        val a = normalize(query)
        val b = normalize(result)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return minOf(a.length, b.length) >= 3 && (a in b || b in a)
    }

    private fun isVersion(label: String): Boolean {
        val l = label.trim()
        if (liveAt.matches(l) || fromSoundtrack.matches(l)) return true
        val w = words(l)
        // A year alone isn't a label: "Prince - 1999".
        return w.any { it in versionWords } && w.all { it in versionWords || it.matches(year) }
    }

    private fun keepsVersion(label: String) = words(label).any { it in keptVersionWords }

    /**
     * "BTS (방탄소년단) 'Dynamite' Official MV" → BTS, Dynamite: an artist, then the song in quotes
     * with nothing but packaging after it. Null when the title isn't shaped like that.
     */
    private fun quotedSong(title: String): LyricsQuery? {
        val m = quoted.matchEntire(feat.replace(title, "").trim()) ?: return null
        val song = cleanTitle(m.groupValues[2].ifEmpty { m.groupValues[3] })
        val artist = titleArtist(m.groupValues[1])
        // "Artist - Song 'Live at X'": the dash split knows better.
        if (dash.containsMatchIn(artist)) return null
        if (cleanTitle(m.groupValues[4]).isNotEmpty() || song.isEmpty() || artist.isEmpty()) return null
        return LyricsQuery(artist, song)
    }

    /**
     * An artist named in a title, as LRCLIB most likely lists it: of a name given in two scripts,
     * "BTS (방탄소년단)" or "아이유 (IU)", the Latin one.
     */
    private fun titleArtist(name: String): String {
        val a = tidy(name)
        val m = twoScripts.matchEntire(a) ?: return a
        val (outside, inside) = m.destructured.toList().map(::tidy)
        val latin = listOf(outside, inside).filter(::isLatin)
        // Both Latin ("Prince (The Artist)") or neither: not a transliteration, leave it.
        return if (latin.size == 1) latin.single() else a
    }

    private fun isLatin(s: String) = s.any { it.isLetter() } &&
        s.filter { it.isLetter() }.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }

    private fun primaryArtist(artist: String): String? {
        val first = artist.split(Regex("""\s*(,|&|\band\b|\bx\b|\bvs\.?|\bwith\b)\s*""", RegexOption.IGNORE_CASE), limit = 2)
        return first.takeIf { it.size == 2 }?.get(0)?.let(::tidy)?.takeIf { it.isNotEmpty() }
    }

    private fun tidy(s: String) = s.replace(Regex("""\s+"""), " ").trim().trim('-', '–', '—', '|', ':', ' ')
        .replace(quotes, "").trim()

    private fun sameName(a: String, b: String) = normalize(a).let { it.isNotEmpty() && it == normalize(b) }

    /** Lowercase words of letters and digits, accents removed: "Beyoncé - Halo!" → [beyonce, halo]. */
    private fun words(s: String): List<String> =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "").lowercase()
            .split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }

    /** [words] run together, for loose comparisons ("Jay-Z" and "JAY Z" are the same). */
    internal fun normalize(s: String) = words(s).joinToString("")
}
