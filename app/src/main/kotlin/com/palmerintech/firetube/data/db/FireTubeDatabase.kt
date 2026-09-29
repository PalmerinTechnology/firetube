package com.palmerintech.firetube.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class, HistoryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class FireTubeDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao

    companion object {
        fun create(context: Context): FireTubeDatabase =
            Room.databaseBuilder(context, FireTubeDatabase::class.java, "firetube.db").build()
    }
}
