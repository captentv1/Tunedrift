package com.musicflow.play.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.musicflow.play.data.TextNormalizer
import com.musicflow.play.data.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class AudioTagEdit(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val year: Int,
    val trackNumber: Int
)

object AudioMetadataHelper {

    /**
     * Extracts metadata and checks embedded cover art using MediaMetadataRetriever.
     */
    suspend fun extractTags(context: Context, uri: Uri, base: TrackEntity): TrackEntity =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.trim()?.takeIf { it.isNotEmpty() } ?: base.title
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.trim()?.takeIf { it.isNotEmpty() } ?: base.artist
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.trim()?.takeIf { it.isNotEmpty() } ?: base.album
                val albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?.trim().orEmpty()
                val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                    ?.trim().orEmpty()
                val yearRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                    ?.take(4)?.toIntOrNull() ?: base.year
                val trackRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')?.trim()?.toIntOrNull() ?: base.trackNumber
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: base.durationMs
                val bitrateBps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toIntOrNull() ?: 0
                val sampleRateHz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                        ?.toIntOrNull() ?: 0
                } else {
                    0
                }
                val hasArt = retriever.embeddedPicture != null

                base.copy(
                    title = title,
                    artist = artist,
                    album = album,
                    albumArtist = albumArtist,
                    genre = genre,
                    year = yearRaw,
                    trackNumber = trackRaw,
                    durationMs = durationMs,
                    bitrateKbps = if (bitrateBps > 0) bitrateBps / 1000 else base.bitrateKbps,
                    sampleRateHz = sampleRateHz,
                    hasEmbeddedArt = hasArt,
                    titleNorm = TextNormalizer.normalize(title),
                    artistNorm = TextNormalizer.normalize(artist),
                    albumNorm = TextNormalizer.normalize(album)
                )
            } catch (_: Exception) {
                base.copy(
                    titleNorm = TextNormalizer.normalize(base.title),
                    artistNorm = TextNormalizer.normalize(base.artist),
                    albumNorm = TextNormalizer.normalize(base.album)
                )
            } finally {
                runCatching { retriever.release() }
            }
        }

    /**
     * Loads album art from embedded metadata, folder cover image, or MediaStore album art.
     */
    suspend fun loadArtworkBitmap(context: Context, track: TrackEntity, maxDimension: Int = 512): Bitmap? =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(track.uri)

            // 1. Embedded artwork inside audio file
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val picture = retriever.embeddedPicture
                if (picture != null && picture.isNotEmpty()) {
                    return@withContext decodeSampledBitmap(picture, maxDimension)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { retriever.release() }
            }

            // 2. Folder cover (cover.jpg / folder.jpg / front.jpg) if SAF tree URI is available
            if (track.folderUri.startsWith("content://")) {
                val treeDoc = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(track.folderUri)) }.getOrNull()
                val coverNames = setOf("cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "front.jpg")
                val coverDoc = treeDoc?.listFiles()?.firstOrNull { f ->
                    f.isFile && (f.name?.lowercase() in coverNames)
                }
                if (coverDoc != null) {
                    runCatching {
                        context.contentResolver.openInputStream(coverDoc.uri)?.use { input ->
                            val bytes = input.readBytes()
                            return@withContext decodeSampledBitmap(bytes, maxDimension)
                        }
                    }
                }
            }

            // 3. MediaStore album art fallback (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && track.mediaStoreId != null) {
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    track.mediaStoreId
                )
                runCatching {
                    return@withContext context.contentResolver.loadThumbnail(
                        contentUri,
                        android.util.Size(maxDimension, maxDimension),
                        null
                    )
                }
            }
            null
        }

    private fun decodeSampledBitmap(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim * 2 || bounds.outHeight / sample > maxDim * 2) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null

        if (decoded.width <= maxDim && decoded.height <= maxDim) return decoded
        val scale = maxDim.toFloat() / maxOf(decoded.width, decoded.height)
        return Bitmap.createScaledBitmap(
            decoded,
            maxOf(1, (decoded.width * scale).toInt()),
            maxOf(1, (decoded.height * scale).toInt()),
            true
        )
    }

    /**
     * Updates tags in Room, MediaStore (where permitted), and ID3v2.3 header for MP3 files via ContentResolver.
     */
    suspend fun writeTags(context: Context, track: TrackEntity, edit: AudioTagEdit): TrackEntity =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(track.uri)

            // Update MediaStore columns when track comes from MediaStore
            if (track.mediaStoreId != null) {
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.TITLE, edit.title)
                        put(MediaStore.Audio.Media.ARTIST, edit.artist)
                        put(MediaStore.Audio.Media.ALBUM, edit.album)
                        if (edit.year > 0) put(MediaStore.Audio.Media.YEAR, edit.year)
                        if (edit.trackNumber > 0) put(MediaStore.Audio.Media.TRACK, edit.trackNumber)
                    }
                    context.contentResolver.update(uri, values, null, null)
                }
            }

            // Write clean ID3v2.3 tags directly to MP3 files if writable via ContentResolver
            if (track.fileName.lowercase().endsWith(".mp3") || track.mimeType.contains("mpeg")) {
                runCatching {
                    writeId3v23ToMp3(context, uri, edit)
                }
            }

            track.copy(
                title = edit.title,
                artist = edit.artist,
                album = edit.album,
                albumArtist = edit.albumArtist,
                genre = edit.genre,
                year = edit.year,
                trackNumber = edit.trackNumber,
                titleNorm = TextNormalizer.normalize(edit.title),
                artistNorm = TextNormalizer.normalize(edit.artist),
                albumNorm = TextNormalizer.normalize(edit.album)
            )
        }

    private fun writeId3v23ToMp3(context: Context, uri: Uri, edit: AudioTagEdit) {
        val tempIn = File.createTempFile("td_tag_in_", ".mp3", context.cacheDir)
        val tempOut = File.createTempFile("td_tag_out_", ".mp3", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempIn).use { output -> input.copyTo(output) }
            } ?: return

            val allBytes = tempIn.readBytes()
            var audioOffset = 0
            var existingApicFrame: ByteArray? = null

            if (allBytes.size >= 10 &&
                allBytes[0] == 'I'.code.toByte() &&
                allBytes[1] == 'D'.code.toByte() &&
                allBytes[2] == '3'.code.toByte()
            ) {
                val tagSize = syncSafeToInt(allBytes, 6)
                audioOffset = minOf(allBytes.size, 10 + tagSize)
                existingApicFrame = extractApicFrame(allBytes, 10, audioOffset)
            }

            val frames = mutableListOf<ByteArray>()
            if (edit.title.isNotEmpty()) frames.add(buildTextFrame("TIT2", edit.title))
            if (edit.artist.isNotEmpty()) frames.add(buildTextFrame("TPE1", edit.artist))
            if (edit.album.isNotEmpty()) frames.add(buildTextFrame("TALB", edit.album))
            if (edit.albumArtist.isNotEmpty()) frames.add(buildTextFrame("TPE2", edit.albumArtist))
            if (edit.genre.isNotEmpty()) frames.add(buildTextFrame("TCON", edit.genre))
            if (edit.year > 0) frames.add(buildTextFrame("TYER", edit.year.toString()))
            if (edit.trackNumber > 0) frames.add(buildTextFrame("TRCK", edit.trackNumber.toString()))
            if (existingApicFrame != null) frames.add(existingApicFrame)

            val payloadSize = frames.sumOf { it.size }
            val header = ByteArray(10)
            header[0] = 'I'.code.toByte()
            header[1] = 'D'.code.toByte()
            header[2] = '3'.code.toByte()
            header[3] = 3 // ID3v2.3
            header[4] = 0
            header[5] = 0
            writeSyncSafeInt(header, 6, payloadSize)

            FileOutputStream(tempOut).use { out ->
                out.write(header)
                for (frame in frames) out.write(frame)
                out.write(allBytes, audioOffset, allBytes.size - audioOffset)
            }

            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                FileInputStream(tempOut).use { input -> input.copyTo(out) }
            }
        } finally {
            tempIn.delete()
            tempOut.delete()
        }
    }

    private fun extractApicFrame(data: ByteArray, start: Int, end: Int): ByteArray? {
        var pos = start
        while (pos + 10 <= end) {
            val id = String(data, pos, 4, Charsets.ISO_8859_1)
            if (id[0].code == 0) break
            val size = ((data[pos + 4].toInt() and 0xFF) shl 24) or
                ((data[pos + 5].toInt() and 0xFF) shl 16) or
                ((data[pos + 6].toInt() and 0xFF) shl 8) or
                (data[pos + 7].toInt() and 0xFF)
            if (size <= 0 || pos + 10 + size > end) break
            if (id == "APIC") {
                return data.copyOfRange(pos, pos + 10 + size)
            }
            pos += 10 + size
        }
        return null
    }

    private fun buildTextFrame(frameId: String, text: String): ByteArray {
        val utf16Bytes = text.toByteArray(Charsets.UTF_16)
        val contentSize = 1 + utf16Bytes.size
        val frame = ByteArray(10 + contentSize)
        val idBytes = frameId.toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(idBytes, 0, frame, 0, 4)
        frame[4] = ((contentSize shr 24) and 0xFF).toByte()
        frame[5] = ((contentSize shr 16) and 0xFF).toByte()
        frame[6] = ((contentSize shr 8) and 0xFF).toByte()
        frame[7] = (contentSize and 0xFF).toByte()
        frame[8] = 0
        frame[9] = 0
        frame[10] = 0x01 // UTF-16 with BOM
        System.arraycopy(utf16Bytes, 0, frame, 11, utf16Bytes.size)
        return frame
    }

    private fun syncSafeToInt(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0x7F) shl 21) or
            ((buf[offset + 1].toInt() and 0x7F) shl 14) or
            ((buf[offset + 2].toInt() and 0x7F) shl 7) or
            (buf[offset + 3].toInt() and 0x7F)
    }

    private fun writeSyncSafeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value shr 21) and 0x7F).toByte()
        buf[offset + 1] = ((value shr 14) and 0x7F).toByte()
        buf[offset + 2] = ((value shr 7) and 0x7F).toByte()
        buf[offset + 3] = (value and 0x7F).toByte()
    }
}
