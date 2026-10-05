package com.palmerintech.firetube.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Upsert
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun track(id: String): TrackEntity?

    // --- Playlists ---

    @Query(
        """
        SELECT p.id, p.name, p.updatedAt,
            (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlistId = p.id) AS trackCount,
            (SELECT t.thumbnailUrl FROM playlist_tracks pt JOIN tracks t ON t.id = pt.trackId
                WHERE pt.playlistId = p.id ORDER BY pt.position LIMIT 1) AS thumbnailUrl
        FROM playlists p
        WHERE p.deleted = 0 AND p.id != :favoritesId
        ORDER BY p.updatedAt DESC
        """,
    )
    fun observePlaylists(favoritesId: String = PlaylistEntity.FAVORITES_ID): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observePlaylist(id: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun playlist(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlists")
    suspend fun allPlaylistsIncludingDeleted(): List<PlaylistEntity>

    @Query(
        """
        SELECT t.* FROM playlist_tracks pt JOIN tracks t ON t.id = pt.trackId
        WHERE pt.playlistId = :playlistId ORDER BY pt.position
        """,
    )
    fun observePlaylistTracks(playlistId: String): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT t.* FROM playlist_tracks pt JOIN tracks t ON t.id = pt.trackId
        WHERE pt.playlistId = :playlistId ORDER BY pt.position
        """,
    )
    suspend fun playlistTracks(playlistId: String): List<TrackEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId)")
    fun observeContains(playlistId: String, trackId: String): Flow<Boolean>

    // Upsert (UPDATE in place), not REPLACE: REPLACE deletes the row and would cascade-wipe its tracks.
    @Upsert
    suspend fun upsertPlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearPlaylistTracks(playlistId: String)

    @Insert
    suspend fun insertPlaylistTracks(rows: List<PlaylistTrackEntity>)

    /** Replace a playlist's contents and metadata in one go. */
    @Transaction
    suspend fun writePlaylist(playlist: PlaylistEntity, tracks: List<TrackEntity>) {
        upsertTracks(tracks)
        upsertPlaylist(playlist)
        clearPlaylistTracks(playlist.id)
        insertPlaylistTracks(tracks.mapIndexed { i, t -> PlaylistTrackEntity(playlist.id, i, t.id) })
    }

    // --- History ---

    @Insert
    suspend fun insertHistory(entry: HistoryEntity)

    /** Most recent plays, one row per track. */
    @Query(
        """
        SELECT t.* FROM tracks t JOIN
            (SELECT trackId, MAX(playedAt) AS lastPlayed FROM history GROUP BY trackId) h
            ON h.trackId = t.id
        ORDER BY h.lastPlayed DESC LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT t.* FROM tracks t JOIN
            (SELECT trackId, MAX(playedAt) AS lastPlayed FROM history GROUP BY trackId) h
            ON h.trackId = t.id
        ORDER BY h.lastPlayed DESC LIMIT :limit
        """,
    )
    suspend fun recent(limit: Int): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM history")
    fun observePlayCount(): Flow<Int>

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    // --- Plays (stats) ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlayIfAbsent(play: PlayEntity)

    /** Only ever grows: saves of the same listen can land out of order. */
    @Query("UPDATE plays SET msListened = MAX(msListened, :msListened) WHERE startedAt = :startedAt AND trackId = :trackId")
    suspend fun extendPlay(trackId: String, startedAt: Long, msListened: Long)

    /** Insert a listen, or update the one already saved for it. */
    @Transaction
    suspend fun savePlay(play: PlayEntity) {
        insertPlayIfAbsent(play)
        extendPlay(play.trackId, play.startedAt, play.msListened)
    }

    @Query("DELETE FROM plays WHERE startedAt < :before")
    suspend fun prunePlays(before: Long)

    @Query("DELETE FROM plays")
    suspend fun clearPlays()

    @Query(
        """
        SELECT t.*, COUNT(*) AS plays, SUM(p.msListened) AS msListened
        FROM plays p JOIN tracks t ON t.id = p.trackId
        WHERE p.startedAt >= :from
        GROUP BY p.trackId
        """,
    )
    fun observeTrackPlays(from: Long): Flow<List<TrackPlays>>

    @Query("SELECT startedAt, msListened FROM plays WHERE startedAt >= :from")
    suspend fun playTimes(from: Long): List<PlayTime>
}
