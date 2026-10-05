package com.palmerintech.firetube.data

import com.palmerintech.firetube.data.db.PlayTime
import com.palmerintech.firetube.data.db.TrackPlays
import com.palmerintech.firetube.extractor.Track
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/** The periods Your stats can show; each runs from the start of the current week/month/year. */
enum class StatsPeriod(val label: String) {
    WEEK("This week"), MONTH("This month"), YEAR("This year"), ALL("All time");

    /** Epoch ms where this period starts, in the user's time zone and (for weeks) locale. */
    fun start(now: Instant, zone: ZoneId, locale: Locale = Locale.getDefault()): Long {
        val today = now.atZone(zone).toLocalDate()
        val first = when (this) {
            WEEK -> today.with(TemporalAdjusters.previousOrSame(WeekFields.of(locale).firstDayOfWeek))
            MONTH -> today.withDayOfMonth(1)
            YEAR -> today.withDayOfYear(1)
            ALL -> return 0L
        }
        return first.atTime(LocalTime.MIDNIGHT).atZone(zone).toInstant().toEpochMilli()
    }
}

data class SongStats(val track: Track, val plays: Int, val msListened: Long)

data class ArtistStats(
    val name: String,
    val plays: Int,
    val msListened: Long,
    val thumbnailUrl: String?,
    /** Their songs played in the period, most played first. */
    val tracks: List<Track>,
)

/** Everything the stats screen shows for one period. */
data class ListeningStats(
    val plays: Int,
    val msListened: Long,
    val topSongs: List<SongStats>,
    val topArtists: List<ArtistStats>,
    /** Listened ms by hour of day (0–23, local time). */
    val byHour: List<Long>,
) {
    val isEmpty: Boolean get() = plays == 0

    companion object {
        const val TOP = 10

        fun of(tracks: List<TrackPlays>, times: List<PlayTime>, zone: ZoneId, top: Int = TOP): ListeningStats {
            val songs = tracks
                .map { SongStats(it.track.toTrack(), it.plays, it.msListened) }
                .sortedWith(compareByDescending<SongStats> { it.plays }.thenByDescending { it.msListened }.thenBy { it.track.title })
            val artists = songs
                .filter { artistKey(it.track.artist).isNotEmpty() }
                .groupBy { artistKey(it.track.artist) }
                .values
                // songs are already most-played first, so each group's first song names and pictures the artist
                .map { group ->
                    val top = group.first().track
                    ArtistStats(displayArtist(top.artist), group.sumOf { it.plays }, group.sumOf { it.msListened }, top.thumbnailUrl, group.map { it.track })
                }
                .sortedWith(compareByDescending<ArtistStats> { it.plays }.thenByDescending { it.msListened }.thenBy { it.name })
            val byHour = LongArray(24)
            times.forEach { byHour[Instant.ofEpochMilli(it.startedAt).atZone(zone).hour] += it.msListened }
            return ListeningStats(
                plays = songs.sumOf { it.plays },
                msListened = songs.sumOf { it.msListened },
                topSongs = songs.take(top),
                topArtists = artists.take(top),
                byHour = byHour.toList(),
            )
        }

        /** YouTube's auto-generated "Artist - Topic" channels are the same artist as "Artist". */
        fun displayArtist(artist: String): String =
            artist.trim().replace(TOPIC_SUFFIX, "").replace(SPACES, " ").trim()

        /** Groups spellings of one artist: no "- Topic", any case, any spacing. */
        fun artistKey(artist: String): String = displayArtist(artist).lowercase(Locale.ROOT)

        private val TOPIC_SUFFIX = Regex("""\s*-\s*topic$""", RegexOption.IGNORE_CASE)
        private val SPACES = Regex("""\s+""")
    }
}

/** "3 h 25 min", "12 min", "45 sec". */
fun formatListened(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
        minutes >= 1 -> "$minutes min"
        else -> "${ms / 1000} sec"
    }
}
