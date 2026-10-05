package com.palmerintech.firetube.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.palmerintech.firetube.data.LibraryRepository
import com.palmerintech.firetube.extractor.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PlaysDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var db: FireTubeDatabase? = null

    @After
    fun close() {
        db?.close()
        context.deleteDatabase(NAME)
    }

    private fun open(): FireTubeDatabase =
        Room.databaseBuilder(context, FireTubeDatabase::class.java, NAME).addMigrations(*FireTubeDatabase.MIGRATIONS).build().also { db = it }

    private val song = Track("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", 213, null)

    /** A database exactly as version 1 created it, from the exported schema, with one song in history. */
    private fun createVersion1() {
        val schema = Json.parseToJsonElement(File("schemas/com.palmerintech.firetube.data.db.FireTubeDatabase/1.json").readText())
            .jsonObject.getValue("database").jsonObject
        context.deleteDatabase(NAME)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(NAME).apply { parentFile?.mkdirs() }, null).use { v1 ->
            schema.getValue("entities").jsonArray.forEach { e ->
                val table = e.jsonObject.getValue("tableName").jsonPrimitive.content
                v1.execSQL(e.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e.jsonObject["indices"]?.jsonArray?.forEach { i ->
                    v1.execSQL(i.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { v1.execSQL(it.jsonPrimitive.content) }
            v1.execSQL("INSERT INTO tracks VALUES ('${song.id}', '${song.title}', '${song.artist}', 213, NULL)")
            v1.execSQL("INSERT INTO history (trackId, playedAt) VALUES ('${song.id}', 1000)")
            v1.version = 1
        }
    }

    @Test
    fun migratesFromVersion1KeepingHistory() = runBlocking {
        createVersion1()
        // Room validates the migrated schema against version 2's on open, and throws if they differ.
        val dao = open().library()
        assertEquals(listOf(song.id), dao.recent(10).map { it.id })
        assertTrue("stats start empty", dao.observeTrackPlays(0).first().isEmpty())
        dao.savePlay(PlayEntity(song.id, 2000, 60_000))
        assertEquals(1, dao.observeTrackPlays(0).first().single().plays)
    }

    @Test
    fun savingTheSameListenAgainUpdatesIt() = runBlocking {
        val library = LibraryRepository(open().library())
        library.recordListen(song, startedAt = 5_000, msListened = 40_000)
        library.recordListen(song, startedAt = 5_000, msListened = 90_000)
        // A late, older save must not shrink it.
        library.recordListen(song, startedAt = 5_000, msListened = 60_000)
        val row = library.trackPlays(0).first().single()
        assertEquals(1, row.plays)
        assertEquals(90_000L, row.msListened)
    }

    @Test
    fun periodsAggregateAndClearingHistoryClearsStats() = runBlocking {
        val library = LibraryRepository(open().library())
        val other = Track("kJQP7kiw5Fk", "Despacito", "Luis Fonsi - Topic", 280, null)
        val now = System.currentTimeMillis()
        library.recordListen(song, now - 10_000_000, 100_000)
        library.recordListen(song, now - 5_000_000, 200_000)
        library.recordListen(other, now - 1_000, 50_000)
        library.recordListen(other, now - 100 * DAY, 50_000)

        val recent = library.trackPlays(now - 20_000_000).first().associateBy { it.track.id }
        assertEquals(2, recent.getValue(song.id).plays)
        assertEquals(300_000L, recent.getValue(song.id).msListened)
        assertEquals(1, recent.getValue(other.id).plays)
        assertEquals(4, library.trackPlays(0).first().sumOf { it.plays })
        val stats = library.listeningStats(now - 20_000_000, java.time.ZoneId.of("UTC")).first()
        assertEquals(3, stats.plays)
        assertEquals("the hour chart comes from the same read", stats.msListened, stats.byHour.sum())

        library.clearHistory()
        assertTrue(library.trackPlays(0).first().isEmpty())
    }

    @Test
    fun oldPlaysArePruned() = runBlocking {
        val dao = open().library()
        val now = System.currentTimeMillis()
        dao.upsertTracks(listOf(TrackEntity.of(song)))
        dao.savePlay(PlayEntity(song.id, now - 800 * DAY, 60_000))
        dao.savePlay(PlayEntity(song.id, now - 300 * DAY, 60_000))
        // The first save of a run prunes anything older than two years.
        LibraryRepository(dao).recordListen(song, now, 60_000)
        assertEquals(listOf(now - 300 * DAY, now), dao.playTimes(0).map { it.startedAt }.sorted())
    }

    @Test
    fun blankItemForUnknownTrackIsIgnored() = runBlocking {
        val library = LibraryRepository(open().library())
        library.recordListen(Track("unknown1234", "", "", 0, null), 1_000, 60_000)
        assertTrue(library.trackPlays(0).first().isEmpty())
    }

    private companion object {
        const val NAME = "plays-test.db"
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
