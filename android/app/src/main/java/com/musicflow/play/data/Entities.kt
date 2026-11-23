package com.musicflow.play.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["titleNorm"]),
        Index(value = ["artistNorm"]),
        Index(value = ["albumNorm"]),
        Index(value = ["folderUri"])
    ]
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val uri: String,
    val mediaStoreId: Long? = null,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String = "",
    val genre: String = "",
    val year: Int = 0,
    val trackNumber: Int = 0,
    val durationMs: Long = 0L,
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 0,
    val mimeType: String = "",
    val fileName: String = "",
    val folderName: String = "",
    val folderUri: String = "",
    val sizeBytes: Long = 0L,
    val dateAddedSec: Long = 0L,
    val dateModifiedSec: Long = 0L,
    val isFavorite: Boolean = false,
    val rating: Int = 0,
    val playCount: Int = 0,
    val lastPlayedMs: Long = 0L,
    val hasEmbeddedArt: Boolean = false,
    val lyricsText: String? = null,
    val syncedLyricsLrc: String? = null,
    val titleNorm: String = "",
    val artistNorm: String = "",
    val albumNorm: String = ""
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val description: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["trackId"])
    ]
)
data class PlaylistTrackCrossRef(
    val playlistId: Long,
    val trackId: Long,
    val position: Int
)

@Entity(tableName = "saf_folders")
data class SafFolderEntity(
    @PrimaryKey val treeUri: String,
    val displayName: String,
    val addedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "queue_items",
    indices = [Index(value = ["trackId"])]
)
data class QueueItemEntity(
    @PrimaryKey(autoGenerate = true) val queueId: Long = 0L,
    val trackId: Long,
    val position: Int
)
