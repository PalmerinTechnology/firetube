package com.palmerintech.firetube.data.db

import androidx.room.Dao
import androidx.room.Insert
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
}
