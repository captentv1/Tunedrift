package com.musicflow.play.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.musicflow.play.data.SafFolderDao
import com.musicflow.play.data.SafFolderEntity
import com.musicflow.play.data.TextNormalizer
import com.musicflow.play.data.TrackDao
import com.musicflow.play.data.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LibraryScanner {

    private val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "m4a", "opus", "ogg", "wav")

    /**
     * Scans MediaStore.Audio.Media + user-selected SAF folders without MANAGE_EXTERNAL_STORAGE.
     * Preserves favorites, ratings, play counts, and cached lyrics across scans.
     */
    suspend fun scanAll(
        context: Context,
        trackDao: TrackDao,
        safFolderDao: SafFolderDao
    ): Int = withContext(Dispatchers.IO) {
        val existingByUri = trackDao.getAllOnce().associateBy { it.uri }
        val discoveredTracks = LinkedHashMap<String, TrackEntity>()

        // 1. Scan MediaStore.Audio.Media
        scanMediaStore(context, existingByUri, discoveredTracks)

        // 2. Scan persisted SAF folders
        val safFolders = safFolderDao.getAllOnce()
        for (folder in safFolders) {
            scanSafTree(context, folder, existingByUri, discoveredTracks)
        }

        // 3. Persist upserts and prune deleted files
        for ((_, track) in discoveredTracks) {
            trackDao.upsert(track)
        }
        if (discoveredTracks.isNotEmpty()) {
            trackDao.deleteMissingUris(discoveredTracks.keys.toList())
        }

        discoveredTracks.size
    }

    private suspend fun scanMediaStore(
        context: Context,
        existingByUri: Map<String, TrackEntity>,
        out: LinkedHashMap<String, TrackEntity>
    ) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Audio.Media.RELATIVE_PATH)
        }

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DURATION} >= 5000"

        runCatching {
            context.contentResolver.query(
                collection,
                projection.toTypedArray(),
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val modCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else {
                    -1
                }

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val fileName = cursor.getString(nameCol).orEmpty()
                    val ext = fileName.substringAfterLast('.', "").lowercase()
                    val mime = cursor.getString(mimeCol).orEmpty()
                    if (ext !in SUPPORTED_EXTENSIONS && !mime.startsWith("audio/")) continue

                    val contentUri = ContentUris.withAppendedId(collection, id)
                    val uriStr = contentUri.toString()
                    val dateModified = cursor.getLong(modCol)
                    val existing = existingByUri[uriStr]

                    val relPath = if (relPathCol >= 0) cursor.getString(relPathCol).orEmpty().trimEnd('/') else ""
                    val folderName = relPath.substringAfterLast('/', "Music")

                    val rawTitle = cursor.getString(titleCol)?.trim().takeIf { !it.isNullOrEmpty() }
                        ?: fileName.substringBeforeLast('.')
                    val rawArtist = cursor.getString(artistCol)?.trim()
                        ?.takeIf { !it.isNullOrEmpty() && it != "<unknown>" } ?: "Artiste inconnu"
                    val rawAlbum = cursor.getString(albumCol)?.trim()
                        ?.takeIf { !it.isNullOrEmpty() && it != "<unknown>" } ?: "Album inconnu"

                    val base = TrackEntity(
                        id = existing?.id ?: 0L,
                        uri = uriStr,
                        mediaStoreId = id,
                        title = rawTitle,
                        artist = rawArtist,
                        album = rawAlbum,
                        albumArtist = existing?.albumArtist.orEmpty(),
                        genre = existing?.genre.orEmpty(),
                        year = cursor.getInt(yearCol),
                        trackNumber = cursor.getInt(trackCol) % 1000,
                        durationMs = cursor.getLong(durCol),
                        bitrateKbps = existing?.bitrateKbps ?: 0,
                        sampleRateHz = existing?.sampleRateHz ?: 0,
                        mimeType = mime,
                        fileName = fileName,
                        folderName = folderName,
                        folderUri = existing?.folderUri.orEmpty(),
                        sizeBytes = cursor.getLong(sizeCol),
                        dateAddedSec = cursor.getLong(addedCol),
                        dateModifiedSec = dateModified,
                        isFavorite = existing?.isFavorite ?: false,
                        rating = existing?.rating ?: 0,
                        playCount = existing?.playCount ?: 0,
                        lastPlayedMs = existing?.lastPlayedMs ?: 0L,
                        hasEmbeddedArt = existing?.hasEmbeddedArt ?: false,
                        lyricsText = existing?.lyricsText,
                        syncedLyricsLrc = existing?.syncedLyricsLrc,
                        titleNorm = TextNormalizer.normalize(rawTitle),
                        artistNorm = TextNormalizer.normalize(rawArtist),
                        albumNorm = TextNormalizer.normalize(rawAlbum)
                    )

                    val enriched = if (existing == null || existing.dateModifiedSec != dateModified) {
                        val withTags = AudioMetadataHelper.extractTags(context, contentUri, base)
                        val lrc = withTags.syncedLyricsLrc ?: LyricsManager.readLocalLrcFile(context, withTags)
                        withTags.copy(syncedLyricsLrc = lrc)
                    } else {
                        base
                    }

                    out[uriStr] = enriched
                }
            }
        }
    }

    private suspend fun scanSafTree(
        context: Context,
        folder: SafFolderEntity,
        existingByUri: Map<String, TrackEntity>,
        out: LinkedHashMap<String, TrackEntity>
    ) {
        val treeUri = Uri.parse(folder.treeUri)
        val rootDoc = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull() ?: return
        if (!rootDoc.exists() || !rootDoc.isDirectory) return

        suspend fun walk(dir: DocumentFile, dirName: String) {
            for (child in dir.listFiles()) {
                if (child.isDirectory) {
                    walk(child, child.name ?: dirName)
                } else if (child.isFile) {
                    val name = child.name.orEmpty()
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in SUPPORTED_EXTENSIONS) continue

                    val uriStr = child.uri.toString()
                    if (out.containsKey(uriStr)) continue

                    val existing = existingByUri[uriStr]
                    val modSec = child.lastModified() / 1000L
                    val baseTitle = name.substringBeforeLast('.')

                    val base = TrackEntity(
                        id = existing?.id ?: 0L,
                        uri = uriStr,
                        mediaStoreId = null,
                        title = existing?.title ?: baseTitle,
                        artist = existing?.artist ?: "Artiste inconnu",
                        album = existing?.album ?: dirName,
                        albumArtist = existing?.albumArtist.orEmpty(),
                        genre = existing?.genre.orEmpty(),
                        year = existing?.year ?: 0,
                        trackNumber = existing?.trackNumber ?: 0,
                        durationMs = existing?.durationMs ?: 0L,
                        bitrateKbps = existing?.bitrateKbps ?: 0,
                        sampleRateHz = existing?.sampleRateHz ?: 0,
                        mimeType = child.type ?: "audio/$ext",
                        fileName = name,
                        folderName = dirName,
                        folderUri = folder.treeUri,
                        sizeBytes = child.length(),
                        dateAddedSec = existing?.dateAddedSec ?: modSec,
                        dateModifiedSec = modSec,
                        isFavorite = existing?.isFavorite ?: false,
                        rating = existing?.rating ?: 0,
                        playCount = existing?.playCount ?: 0,
                        lastPlayedMs = existing?.lastPlayedMs ?: 0L,
                        hasEmbeddedArt = existing?.hasEmbeddedArt ?: false,
                        lyricsText = existing?.lyricsText,
                        syncedLyricsLrc = existing?.syncedLyricsLrc,
                        titleNorm = TextNormalizer.normalize(existing?.title ?: baseTitle),
                        artistNorm = TextNormalizer.normalize(existing?.artist ?: "Artiste inconnu"),
                        albumNorm = TextNormalizer.normalize(existing?.album ?: dirName)
                    )

                    val enriched = if (existing == null || existing.dateModifiedSec != modSec) {
                        val withTags = AudioMetadataHelper.extractTags(context, child.uri, base)
                        val lrc = withTags.syncedLyricsLrc ?: LyricsManager.readLocalLrcFile(context, withTags)
                        withTags.copy(syncedLyricsLrc = lrc)
                    } else {
                        base
                    }

                    out[uriStr] = enriched
                }
            }
        }

        walk(rootDoc, folder.displayName)
    }
}
