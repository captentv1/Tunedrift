package com.musicflow.play.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY titleNorm ASC")
    fun observeAllTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isFavorite = 1 ORDER BY titleNorm ASC")
    fun observeFavorites(): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT * FROM tracks
        WHERE titleNorm LIKE '%' || :normQuery || '%'
           OR artistNorm LIKE '%' || :normQuery || '%'
           OR albumNorm LIKE '%' || :normQuery || '%'
        ORDER BY titleNorm ASC
        """
    )
    fun searchTracks(normQuery: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): TrackEntity?

    @Query("SELECT * FROM tracks")
    suspend fun getAllOnce(): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tracks: List<TrackEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(track: TrackEntity): Long

    @Update
    suspend fun update(track: TrackEntity)

    @Query("UPDATE tracks SET isFavorite = :favorite WHERE id = :trackId")
    suspend fun setFavorite(trackId: Long, favorite: Boolean)

    @Query("UPDATE tracks SET rating = :rating WHERE id = :trackId")
    suspend fun setRating(trackId: Long, rating: Int)

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedMs = :timestampMs WHERE id = :trackId")
    suspend fun recordPlay(trackId: Long, timestampMs: Long = System.currentTimeMillis())

    @Query("UPDATE tracks SET syncedLyricsLrc = :lrc, lyricsText = :plain WHERE id = :trackId")
    suspend fun updateLyrics(trackId: Long, lrc: String?, plain: String?)

    @Query("DELETE FROM tracks WHERE uri NOT IN (:activeUris)")
    suspend fun deleteMissingUris(activeUris: List<String>)

    @Query("DELETE FROM tracks WHERE id = :trackId")
    suspend fun deleteById(trackId: Long)
}

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY updatedAtMs DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN playlist_tracks pt ON t.id = pt.trackId
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.position ASC
        """
    )
    fun observePlaylistTracks(playlistId: Long): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN playlist_tracks pt ON t.id = pt.trackId
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.position ASC
        """
    )
    suspend fun getPlaylistTracksOnce(playlistId: Long): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, updatedAtMs = :updatedAtMs WHERE id = :playlistId")
    suspend fun renamePlaylist(playlistId: Long, name: String, updatedAtMs: Long = System.currentTimeMillis())

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRef(ref: PlaylistTrackCrossRef)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Transaction
    suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        val pos = nextPosition(playlistId)
        insertCrossRef(PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId, position = pos))
    }
}

@Dao
interface SafFolderDao {

    @Query("SELECT * FROM saf_folders ORDER BY addedAtMs DESC")
    fun observeFolders(): Flow<List<SafFolderEntity>>

    @Query("SELECT * FROM saf_folders")
    suspend fun getAllOnce(): List<SafFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: SafFolderEntity)

    @Query("DELETE FROM saf_folders WHERE treeUri = :treeUri")
    suspend fun delete(treeUri: String)
}

@Dao
interface QueueDao {

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN queue_items q ON t.id = q.trackId
        ORDER BY q.position ASC
        """
    )
    fun observeQueueTracks(): Flow<List<TrackEntity>>

    @Query("DELETE FROM queue_items")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<QueueItemEntity>)

    @Transaction
    suspend fun replaceQueue(trackIds: List<Long>) {
        clear()
        val items = trackIds.mapIndexed { idx, id ->
            QueueItemEntity(trackId = id, position = idx)
        }
        if (items.isNotEmpty()) {
            insertAll(items)
        }
    }
}
