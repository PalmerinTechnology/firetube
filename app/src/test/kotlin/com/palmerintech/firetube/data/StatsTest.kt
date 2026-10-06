package com.palmerintech.firetube.data

import com.palmerintech.firetube.data.db.PlayTime
import com.palmerintech.firetube.data.db.TrackEntity
import com.palmerintech.firetube.data.db.TrackPlays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class StatsTest {
    private val zone = ZoneId.of("America/Denver")

    private fun at(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant()

    private fun plays(id: String, artist: String, plays: Int, ms: Long = plays * 180_000L) =
        TrackPlays(TrackEntity(id, "Song $id", artist, 200, "https://img/$id"), plays, ms)

    @Test
    fun periodsStartAtLocalMidnight() {
        // Wednesday 2026-10-07, 15:30 in Denver.
        val now = at(2026, 10, 7, 15, 30)
        assertEquals(at(2026, 10, 5).toEpochMilli(), StatsPeriod.WEEK.start(now, zone, Locale.UK)) // Monday
        assertEquals(at(2026, 10, 4).toEpochMilli(), StatsPeriod.WEEK.start(now, zone, Locale.US)) // Sunday
        assertEquals(at(2026, 10, 1).toEpochMilli(), StatsPeriod.MONTH.start(now, zone))
        assertEquals(at(2026, 1, 1).toEpochMilli(), StatsPeriod.YEAR.start(now, zone))
        assertEquals(0L, StatsPeriod.ALL.start(now, zone))
    }

    @Test
    fun weekStartsTodayOnItsFirstDay() {
        val mondayMorning = at(2026, 10, 5, 0, 5)
        assertEquals(at(2026, 10, 5).toEpochMilli(), StatsPeriod.WEEK.start(mondayMorning, zone, Locale.UK))
    }

    @Test
    fun periodUsesTheLocalDateNotUtc() {
        // 2026-11-01 03:00 UTC is still October 31 in Denver.
        val now = Instant.parse("2026-11-01T03:00:00Z")
        assertEquals(at(2026, 10, 1).toEpochMilli(), StatsPeriod.MONTH.start(now, zone))
    }

    @Test
    fun totalsAndTopSongs() {
        val stats = ListeningStats.of(
            listOf(plays("a", "X", 2), plays("b", "Y", 5), plays("c", "Z", 2, ms = 999_000), plays("d", "W", 1)),
            emptyList(),
            zone,
            top = 3,
        )
        assertEquals(10, stats.plays)
        assertEquals(2 * 180_000L + 5 * 180_000L + 999_000L + 180_000L, stats.msListened)
        // Most plays first; ties go to the one listened to longer.
        assertEquals(listOf("b", "c", "a"), stats.topSongs.map { it.track.id })
        assertEquals(3, stats.topArtists.size)
    }

    @Test
    fun artistsAreGroupedAcrossTopicChannelsCaseAndSpacing() {
        val stats = ListeningStats.of(
            listOf(
                plays("a", "Daft Punk - Topic", 3),
                plays("b", "daft punk", 1),
                plays("c", "Daft  Punk ", 2),
                plays("d", "Justice", 4),
                plays("e", "", 9),
            ),
            emptyList(),
            zone,
        )
        assertEquals(listOf("Daft Punk", "Justice"), stats.topArtists.map { it.name })
        val daft = stats.topArtists.first()
        assertEquals(6, daft.plays)
        assertEquals("pictured by their most played song", "https://img/a", daft.thumbnailUrl)
        assertEquals(listOf("a", "c", "b"), daft.tracks.map { it.id })
        // Songs without an artist still count towards totals and top songs.
        assertEquals(19, stats.plays)
        assertEquals("e", stats.topSongs.first().track.id)
    }

    @Test
    fun artistNames() {
        assertEquals("Queen", ListeningStats.displayArtist("Queen - Topic"))
        assertEquals("Queen", ListeningStats.displayArtist("  Queen -topic "))
        assertEquals("Topic", ListeningStats.displayArtist("Topic"))
        assertEquals(ListeningStats.artistKey("QUEEN"), ListeningStats.artistKey("queen - Topic"))
        assertEquals("Shakira", ListeningStats.displayArtist("Shakira and 2 more"))
        assertEquals("Simon and Garfunkel", ListeningStats.displayArtist("Simon and Garfunkel"))
    }

    @Test
    fun listeningByLocalHour() {
        val stats = ListeningStats.of(
            listOf(plays("a", "X", 3)),
            listOf(
                PlayTime(at(2026, 10, 7, 21, 10).toEpochMilli(), 100_000),
                PlayTime(at(2026, 10, 8, 21, 59).toEpochMilli(), 50_000),
                PlayTime(at(2026, 10, 8, 7, 0).toEpochMilli(), 30_000),
            ),
            zone,
        )
        assertEquals(24, stats.byHour.size)
        assertEquals(150_000L, stats.byHour[21])
        assertEquals(30_000L, stats.byHour[7])
        assertEquals(180_000L, stats.byHour.sum())
    }

    @Test
    fun emptyStats() {
        val stats = ListeningStats.of(emptyList(), emptyList(), zone)
        assertTrue(stats.isEmpty)
        assertTrue(stats.topSongs.isEmpty() && stats.topArtists.isEmpty())
    }
}
