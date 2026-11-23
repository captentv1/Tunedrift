package com.musicflow.play.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.musicflow.play.media.AudioMetadataHelper
import com.musicflow.play.media.AudioTagEdit
import com.musicflow.play.media.LibraryScanner
import com.musicflow.play.media.LrcLibCandidate
import com.musicflow.play.media.LyricsManager
import kotlinx.coroutines.flow.Flow

class MusicRepository(private val context: Context) {

    private val db = TunedriftDatabase.getInstance(context)
    private val trackDao = db.trackDao()
    private val playlistDao = db.playlistDao()
    private val safFolderDao = db.safFolderDao()
    private val queueDao = db.queueDao()

    val allTracks: Flow<List<TrackEntity>> = trackDao.observeAllTracks()
    val favoriteTracks: Flow<List<TrackEntity>> = trackDao.observeFavorites()
    val playlists: Flow<List<PlaylistEntity>> = playlistDao.observePlaylists()
    val safFolders: Flow<List<SafFolderEntity>> = safFolderDao.observeFolders()
    val persistedQueue: Flow<List<TrackEntity>> = queueDao.observeQueueTracks()

    fun searchTracks(query: String): Flow<List<TrackEntity>> {
        val norm = TextNormalizer.normalize(query)
        return if (norm.isEmpty()) trackDao.observeAllTracks() else trackDao.searchTracks(norm)
    }

    fun observePlaylistTracks(playlistId: Long): Flow<List<TrackEntity>> =
        playlistDao.observePlaylistTracks(playlistId)

    suspend fun getTrackById(id: Long): TrackEntity? = trackDao.getById(id)

    suspend fun getTrackByUri(uri: String): TrackEntity? = trackDao.getByUri(uri)

    suspend fun rescanLibrary(): Int = LibraryScanner.scanAll(context, trackDao, safFolderDao)

    suspend fun registerSafFolder(treeUri: Uri): SafFolderEntity {
        val doc = DocumentFile.fromTreeUri(context, treeUri)
        val name = doc?.name ?: treeUri.lastPathSegment ?: "Dossier audio"
        val entity = SafFolderEntity(treeUri = treeUri.toString(), displayName = name)
        safFolderDao.insert(entity)
        return entity
    }

    suspend fun removeSafFolder(treeUri: String) {
        safFolderDao.delete(treeUri)
    }

    suspend fun toggleFavorite(track: TrackEntity) {
        trackDao.setFavorite(track.id, !track.isFavorite)
    }

    suspend fun setFavorite(trackId: Long, favorite: Boolean) {
        trackDao.setFavorite(trackId, favorite)
    }

    suspend fun setRating(trackId: Long, rating: Int) {
        trackDao.setRating(trackId, rating.coerceIn(0, 5))
    }

    suspend fun recordPlay(trackId: Long) {
        trackDao.recordPlay(trackId)
    }

    suspend fun editTrackTags(track: TrackEntity, edit: AudioTagEdit): TrackEntity {
        val updated = AudioMetadataHelper.writeTags(context, track, edit)
        trackDao.update(updated)
        return updated
    }

    suspend fun fetchOnlineLyrics(track: TrackEntity): List<LrcLibCandidate> =
        LyricsManager.searchLrcLib(track.title, track.artist, track.album, track.durationMs)

    suspend fun syncLyricsFromLrcLib(track: TrackEntity): Boolean {
        val match = LyricsManager.findBestVerifiedMatch(track) ?: return false
        val lrc = match.syncedLyrics ?: return false
        LyricsManager.saveLrcLyrics(context, track, lrc)
        trackDao.updateLyrics(track.id, lrc, match.plainLyrics ?: track.lyricsText)
        return true
    }

    suspend fun applyLyricsCandidate(track: TrackEntity, candidate: LrcLibCandidate) {
        candidate.syncedLyrics?.let { LyricsManager.saveLrcLyrics(context, track, it) }
        trackDao.updateLyrics(track.id, candidate.syncedLyrics, candidate.plainLyrics ?: track.lyricsText)
    }

    suspend fun createPlaylist(name: String, description: String = ""): Long {
        return playlistDao.insertPlaylist(
            PlaylistEntity(name = name.trim(), description = description.trim())
        )
    }

    suspend fun renamePlaylist(playlistId: Long, newName: String) {
        playlistDao.renamePlaylist(playlistId, newName.trim())
    }

    suspend fun deletePlaylist(playlistId: Long) {
        playlistDao.deletePlaylist(playlistId)
    }

    suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        playlistDao.addTrackToPlaylist(playlistId, trackId)
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) {
        playlistDao.removeTrackFromPlaylist(playlistId, trackId)
    }

    suspend fun saveQueue(trackIds: List<Long>) {
        queueDao.replaceQueue(trackIds)
    }
}
