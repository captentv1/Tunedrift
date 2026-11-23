package com.musicflow.play.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        SafFolderEntity::class,
        QueueItemEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TunedriftDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun safFolderDao(): SafFolderDao
    abstract fun queueDao(): QueueDao

    companion object {
        @Volatile
        private var INSTANCE: TunedriftDatabase? = null

        fun getInstance(context: Context): TunedriftDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TunedriftDatabase::class.java,
                    "tunedrift_native.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
