package com.musicflow.play.downloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.musicflow.play.BuildConfig
import com.musicflow.play.WorkService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Variante GitHub : recherche en ligne et téléchargement MusicFlow natif vers MediaStore / SAF.
 */
object PlatformDownloaderProvider : OnlineDownloaderPort {

    override val isSupported: Boolean = true

    private val _progress = MutableStateFlow(DownloadProgress())
    override val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    override suspend fun searchOnline(query: String): List<OnlineTrackResult> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://api.deezer.com/search?q=$encoded&limit=25"
            val body = httpGet(url) ?: return@withContext emptyList()
            val root = JSONObject(body)
            val data = root.optJSONArray("data") ?: return@withContext emptyList()
            val list = mutableListOf<OnlineTrackResult>()
            for (i in 0 until data.length()) {
                val obj = data.getJSONObject(i)
                val artistObj = obj.optJSONObject("artist")
                val albumObj = obj.optJSONObject("album")
                list.add(
                    OnlineTrackResult(
                        id = obj.optLong("id").toString(),
                        title = obj.optString("title"),
                        artist = artistObj?.optString("name").orEmpty(),
                        album = albumObj?.optString("title").orEmpty(),
                        durationSec = obj.optInt("duration", 0),
                        coverUrl = albumObj?.optString("cover_medium").orEmpty(),
                        streamUrl = obj.optString("preview")
                    )
                )
            }
            list
        }

    override suspend fun downloadTrack(
        context: Context,
        item: OnlineTrackResult,
        targetFolderUri: Uri?,
        onCompleted: suspend () -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        if (item.streamUrl.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Flux audio indisponible"))
        }
        val safeName = "${item.artist} - ${item.title}"
            .replace("[\\\\/:*?\"<>|]".toRegex(), "_")
            .take(120) + ".mp3"

        _progress.value = DownloadProgress(isDownloading = true, currentTitle = item.title, percent = 10)
        WorkService.start(context, "Téléchargement : ${item.title}", 10)

        try {
            val conn = (URL(item.streamUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "Tunedrift/${BuildConfig.VERSION_NAME}")
            }
            if (conn.responseCode != 200) {
                conn.disconnect()
                throw IllegalStateException("Erreur serveur (${conn.responseCode})")
            }

            val total = conn.contentLengthLong.coerceAtLeast(1L)
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var readTotal = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    readTotal += n
                    val pct = ((readTotal * 90) / total).toInt().coerceIn(10, 95)
                    _progress.value = DownloadProgress(isDownloading = true, currentTitle = item.title, percent = pct)
                    WorkService.update(context, "Téléchargement : ${item.title}", pct)
                }
                out.toByteArray()
            }
            conn.disconnect()

            if (targetFolderUri != null) {
                val tree = DocumentFile.fromTreeUri(context, targetFolderUri)
                val created = tree?.createFile("audio/mpeg", safeName)
                if (created != null) {
                    context.contentResolver.openOutputStream(created.uri)?.use { it.write(bytes) }
                }
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, safeName)
                    put(MediaStore.Audio.Media.TITLE, item.title)
                    put(MediaStore.Audio.Media.ARTIST, item.artist)
                    put(MediaStore.Audio.Media.ALBUM, item.album)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Tunedrift")
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }
                }
                val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Impossible d'écrire dans MediaStore")
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                }
            }

            onCompleted()
            _progress.value = DownloadProgress(isDownloading = false, lastMessage = "Téléchargé : ${item.title}")
            Result.success(safeName)
        } catch (e: Exception) {
            _progress.value = DownloadProgress(isDownloading = false, lastMessage = e.message ?: "Échec")
            Result.failure(e)
        } finally {
            WorkService.stop(context)
        }
    }

    private fun httpGet(urlString: String): String? {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("User-Agent", "Tunedrift/${BuildConfig.VERSION_NAME}")
        }
        return try {
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            conn.disconnect()
        }
    }
}
