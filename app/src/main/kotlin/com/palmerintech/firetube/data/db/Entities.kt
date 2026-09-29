package com.palmerintech.firetube.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.palmerintech.firetube.extractor.Track

/** Track metadata, shared by playlists, history and downloads. */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
) {
    fun toTrack() = Track(id, title, artist, durationSeconds, thumbnailUrl)

    companion object {
        fun of(t: Track) = TrackEntity(t.id, t.title, t.artist, t.durationSeconds, t.thumbnailUrl)
    }
}

/**
 * A playlist. Ids are UUID strings so they're stable across devices for Firestore sync.
 * Favorites is just the playlist with id [FAVORITES_ID].
 * Deletions are soft ([deleted]) so they propagate through sync.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
    /** Set when imported from a YouTube playlist. */
    val sourceUrl: String? = null,
) {
    companion object {
        const val FAVORITES_ID = "favorites"
    }
}

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "position"],
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"]),
    ],
    indices = [Index("trackId")],
)
data class PlaylistTrackEntity(
    val playlistId: String,
    val position: Int,
    val trackId: String,
)

@Entity(
    tableName = "history",
    foreignKeys = [ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"])],
    indices = [Index("trackId"), Index("playedAt")],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val trackId: String,
    val playedAt: Long,
)

/** Playlist row for list screens. */
data class PlaylistWithCount(
    val id: String,
    val name: String,
    val updatedAt: Long,
    val trackCount: Int,
    val thumbnailUrl: String?,
)
