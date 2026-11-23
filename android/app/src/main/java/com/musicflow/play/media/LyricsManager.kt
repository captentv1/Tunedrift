package com.musicflow.play.media

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.musicflow.play.BuildConfig
import com.musicflow.play.data.TextNormalizer
import com.musicflow.play.data.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

data class LyricLine(
    val timestampMs: Long,
    val text: String
)

data class LrcLibCandidate(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationSec: Double,
    val syncedLyrics: String?,
    val plainLyrics: String?
)

object LyricsManager {

    private val LRC_LINE_REGEX = "^((?:\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?\\])+)(.*)$".toRegex()
    private val TIMESTAMP_REGEX = "\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]".toRegex()

    /**
     * Parses LRC content into sorted timestamped lines.
     */
    fun parseLrc(lrcContent: String?): List<LyricLine> {
        if (lrcContent.isNullOrBlank()) return emptyList()
        val result = mutableListOf<LyricLine>()
        for (rawLine in lrcContent.lines()) {
            val trimmed = rawLine.trim()
            val match = LRC_LINE_REGEX.matchEntire(trimmed) ?: continue
            val tagsPart = match.groupValues[1]
            val lyricText = match.groupValues[2].trim()
            for (tsMatch in TIMESTAMP_REGEX.findAll(tagsPart)) {
                val min = tsMatch.groupValues[1].toLongOrNull() ?: 0L
                val sec = tsMatch.groupValues[2].toLongOrNull() ?: 0L
                val fracStr = tsMatch.groupValues[3]
                val ms = when (fracStr.length) {
                    0 -> 0L
                    1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                    else -> fracStr.take(3).toLongOrNull() ?: 0L
                }
                val totalMs = min * 60_000L + sec * 1000L + ms
                result.add(LyricLine(timestampMs = totalMs, text = lyricText))
            }
        }
        return result.sortedBy { it.timestampMs }
    }

    /**
     * Finds the active lyric line index for the given playback position in milliseconds.
     */
    fun findActiveLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var active = -1
        for (i in lines.indices) {
            if (lines[i].timestampMs <= positionMs) {
                active = i
            } else {
                break
            }
        }
        return active
    }

    /**
     * Reads sibling .lrc file via SAF tree folder or MediaStore relative path (no MANAGE_EXTERNAL_STORAGE).
     */
    suspend fun readLocalLrcFile(context: Context, track: TrackEntity): String? =
        withContext(Dispatchers.IO) {
            val baseName = track.fileName.substringBeforeLast('.')
            if (baseName.isBlank()) return@withContext null
            val expectedLrcName = "$baseName.lrc"

            // 1. Check inside the authorized SAF tree folder if available
            if (track.folderUri.startsWith("content://")) {
                val treeDoc = runCatching {
                    DocumentFile.fromTreeUri(context, Uri.parse(track.folderUri))
                }.getOrNull()
                val lrcDoc = treeDoc?.listFiles()?.firstOrNull { doc ->
                    doc.isFile && doc.name.equals(expectedLrcName, ignoreCase = true)
                }
                if (lrcDoc != null) {
                    val content = runCatching {
                        context.contentResolver.openInputStream(lrcDoc.uri)?.bufferedReader(Charsets.UTF_8)?.use {
                            it.readText()
                        }
                    }.getOrNull()
                    if (!content.isNullOrBlank()) return@withContext content
                }
            }

            // 2. Check app-private lyrics cache folder (for tracks without SAF tree write access)
            val cachedLrc = File(File(context.filesDir, "lyrics"), "${track.id}.lrc")
            if (cachedLrc.exists()) {
                return@withContext runCatching { cachedLrc.readText(Charsets.UTF_8) }.getOrNull()
            }

            // 3. Query MediaStore.Files for sibling .lrc on Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && track.folderName.isNotEmpty()) {
                val collection = MediaStore.Files.getContentUri("external")
                val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME)
                val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?"
                val args = arrayOf(expectedLrcName)
                runCatching {
                    context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val id = cursor.getLong(0)
                            val fileUri = Uri.withAppendedPath(collection, id.toString())
                            val text = context.contentResolver.openInputStream(fileUri)?.bufferedReader(Charsets.UTF_8)?.use {
                                it.readText()
                            }
                            if (!text.isNullOrBlank()) return@withContext text
                        }
                    }
                }
            }
            null
        }

    /**
     * Saves synced .lrc lyrics next to the song via SAF when a tree URI is granted,
     * and always stores a local copy in app filesDir/lyrics so lyrics persist reliably.
     */
    suspend fun saveLrcLyrics(context: Context, track: TrackEntity, lrcContent: String): Boolean =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "lyrics").apply { mkdirs() }
            runCatching {
                File(dir, "${track.id}.lrc").writeText(lrcContent, Charsets.UTF_8)
            }

            val baseName = track.fileName.substringBeforeLast('.')
            if (baseName.isNotBlank() && track.folderUri.startsWith("content://")) {
                val treeDoc = runCatching {
                    DocumentFile.fromTreeUri(context, Uri.parse(track.folderUri))
                }.getOrNull()
                if (treeDoc != null && treeDoc.canWrite()) {
                    val expectedName = "$baseName.lrc"
                    val existing = treeDoc.listFiles().firstOrNull {
                        it.isFile && it.name.equals(expectedName, ignoreCase = true)
                    }
                    val target = existing ?: treeDoc.createFile("application/octet-stream", expectedName)
                    if (target != null) {
                        val wrote = runCatching {
                            context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                                out.write(lrcContent.toByteArray(Charsets.UTF_8))
                            }
                            true
                        }.getOrDefault(false)
                        if (wrote) return@withContext true
                    }
                }
            }
            true
        }

    /**
     * Queries LRCLIB API (https://lrclib.net/api/get & /api/search) for synced and plain lyrics.
     */
    suspend fun searchLrcLib(
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0L
    ): List<LrcLibCandidate> = withContext(Dispatchers.IO) {
        val results = mutableListOf<LrcLibCandidate>()
        val durationSec = if (durationMs > 0L) durationMs / 1000.0 else 0.0

        // 1. Direct match via /api/get when duration is known
        if (title.isNotBlank() && artist.isNotBlank() && durationSec > 0.0) {
            runCatching {
                val qTitle = URLEncoder.encode(title, "UTF-8")
                val qArtist = URLEncoder.encode(artist, "UTF-8")
                val qAlbum = URLEncoder.encode(album, "UTF-8")
                val url = "https://lrclib.net/api/get?track_name=$qTitle&artist_name=$qArtist&album_name=$qAlbum&duration=${durationSec.toInt()}"
                val body = httpGet(url)
                if (body != null) {
                    val obj = JSONObject(body)
                    parseCandidate(obj)?.let { results.add(it) }
                }
            }
        }

        // 2. Search fallback via /api/search
        runCatching {
            val queryStr = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
            val encoded = URLEncoder.encode(queryStr, "UTF-8")
            val body = httpGet("https://lrclib.net/api/search?q=$encoded")
            if (body != null) {
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val cand = parseCandidate(arr.getJSONObject(i)) ?: continue
                    if (results.none { it.id == cand.id }) {
                        results.add(cand)
                    }
                }
            }
        }

        results
    }

    /**
     * Selects the best verified match from LRCLIB (matching title, artist, duration within 3s, and synced lines).
     */
    suspend fun findBestVerifiedMatch(track: TrackEntity): LrcLibCandidate? {
        val candidates = searchLrcLib(
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs
        )
        val normTitle = TextNormalizer.normalize(track.title)
        val normArtist = TextNormalizer.normalize(track.artist)
        val trackDurationSec = track.durationMs / 1000.0

        return candidates.firstOrNull { cand ->
            if (cand.syncedLyrics.isNullOrBlank()) return@firstOrNull false
            val titleMatch = TextNormalizer.normalize(cand.trackName) == normTitle ||
                TextNormalizer.normalize(cand.trackName).contains(normTitle)
            val artistMatch = normArtist.isEmpty() ||
                TextNormalizer.normalize(cand.artistName).contains(normArtist) ||
                normArtist.contains(TextNormalizer.normalize(cand.artistName))
            val durationOk = trackDurationSec <= 0.0 || cand.durationSec <= 0.0 ||
                abs(cand.durationSec - trackDurationSec) <= 3.5
            titleMatch && artistMatch && durationOk
        }
    }

    private fun parseCandidate(obj: JSONObject): LrcLibCandidate? {
        val id = obj.optLong("id", -1L)
        if (id < 0L) return null
        val synced = obj.optString("syncedLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        val plain = obj.optString("plainLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        if (synced == null && plain == null) return null
        return LrcLibCandidate(
            id = id,
            trackName = obj.optString("trackName", ""),
            artistName = obj.optString("artistName", ""),
            albumName = obj.optString("albumName", ""),
            durationSec = obj.optDouble("duration", 0.0),
            syncedLyrics = synced,
            plainLyrics = plain
        )
    }

    private fun httpGet(urlString: String): String? {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("User-Agent", "Tunedrift/${BuildConfig.VERSION_NAME} (https://github.com/captentv1/tunedrift)")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }
}
