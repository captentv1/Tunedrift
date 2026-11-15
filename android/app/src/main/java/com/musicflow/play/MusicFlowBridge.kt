package com.musicflow.play

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.documentfile.provider.DocumentFile
import com.musicflow.app.SpotifyScrape
import com.musicflow.app.DownloadForegroundService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/**
 * What MusicFlow's page (shown in the Download tab) asks the phone for, under the name it expects ("Android").
 * Same behavior as in the MusicFlow app; the answers go back to MusicFlow's frame through the player page.
 * MusicFlow's own updates are not offered here: the player is updated as a whole.
 */
class MusicFlowBridge(private val activity: MainActivity) {

    companion object {
        const val PREF_TREE_URI = "musicflow_tree_uri"
    }

    private val prefs get() = activity.getSharedPreferences("musicflow", android.content.Context.MODE_PRIVATE)

    private fun allowed() = activity.isLocalPage

    private fun tree(): DocumentFile? {
        val uriStr = prefs.getString(PREF_TREE_URI, null) ?: return null
        return try { DocumentFile.fromTreeUri(activity, Uri.parse(uriStr)) } catch (e: Exception) { null }
    }

    private fun isSimpleName(name: String) =
        name.isNotBlank() && !name.contains('/') && !name.contains('\\') && name != "." && name != ".."

    private fun isAppFile(path: String): Boolean = try {
        val f = File(path).canonicalPath
        listOfNotNull(activity.getExternalFilesDir(Environment.DIRECTORY_MUSIC), File(activity.filesDir, "Music"), activity.cacheDir)
            .any { f.startsWith(it.canonicalPath + File.separator) }
    } catch (e: Exception) { false }

    fun chosenFolderLabel(): String = try { tree()?.name ?: "" } catch (e: Exception) { "" }

    @JavascriptInterface fun isAndroidApp(): Boolean = true

    @JavascriptInterface
    fun isFirstLaunch(): Boolean {
        if (prefs.getBoolean("deja_lance", false)) return false
        prefs.edit().putBoolean("deja_lance", true).apply()
        return true
    }

    /** MusicFlow's version in this build, so its page doesn't offer to install the MusicFlow app. */
    @JavascriptInterface fun appVersion(): String = BuildConfig.MF_VERSION

    /** The player's theme sets the status bar. */
    @JavascriptInterface fun setLightStatusBar(light: Boolean) { }

    @JavascriptInterface fun installUpdate(apkUrl: String) { }
    @JavascriptInterface fun updateReady(): String = ""
    @JavascriptInterface fun installDownloadedUpdate() { }

    @JavascriptInterface
    fun chooseFolder() {
        if (!allowed()) return
        activity.runOnUiThread { activity.chooseMusicFlowFolder() }
    }

    @JavascriptInterface fun getChosenFolderLabel(): String = chosenFolderLabel()

    @JavascriptInterface
    fun openExternal(url: String) {
        if (!allowed()) return
        if (Uri.parse(url).scheme?.lowercase() !in setOf("https", "http", "spotify", "deezer", "vnd.youtube")) return
        activity.runOnUiThread {
            try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        }
    }

    @JavascriptInterface
    fun shareText(text: String) {
        if (!allowed()) return
        activity.runOnUiThread {
            try {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                activity.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { }
        }
    }

    @JavascriptInterface
    fun startVoiceSearch(): Boolean {
        if (!allowed()) return false
        return activity.startVoiceSearch()
    }

    @JavascriptInterface
    fun listChosenFolderDetails(): String {
        if (!allowed()) return "[]"
        val uriStr = prefs.getString(PREF_TREE_URI, null) ?: return "[]"
        return try {
            val tree = Uri.parse(uriStr)
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
            val out = JSONArray()
            activity.contentResolver.query(children, arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_SIZE,
                android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    out.put(JSONObject().put("nom", c.getString(0) ?: "").put("taille", c.getLong(1)).put("date", c.getLong(2) / 1000))
                }
            }
            out.toString()
        } catch (e: Exception) { "[]" }
    }

    @JavascriptInterface
    fun listChosenFolder(): String {
        if (!allowed()) return "[]"
        val uriStr = prefs.getString(PREF_TREE_URI, null) ?: return "[]"
        return try {
            val tree = Uri.parse(uriStr)
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
            val names = JSONArray()
            activity.contentResolver.query(children,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) c.getString(0)?.let { names.put(it) }
            }
            names.toString()
        } catch (e: Exception) { "[]" }
    }

    @JavascriptInterface
    fun copyFromChosenFolder(name: String): String {
        if (!allowed() || !isSimpleName(name)) return ""
        return try {
            val doc = tree()?.findFile(name) ?: return ""
            val dest = File(File(activity.cacheDir, "edition").apply { mkdirs() }, name)
            activity.contentResolver.openInputStream(doc.uri)?.use { inp -> dest.outputStream().use { inp.copyTo(it) } }
            dest.absolutePath
        } catch (e: Exception) { "" }
    }

    @JavascriptInterface
    fun deleteFromChosenFolder(name: String): Boolean {
        if (!allowed() || !isSimpleName(name)) return false
        return try { tree()?.findFile(name)?.delete() ?: false } catch (e: Exception) { false }
    }

    @JavascriptInterface
    fun saveTextToChosenFolder(name: String, mime: String, text: String): String {
        if (!allowed() || !isSimpleName(name)) return ""
        return try {
            val dir = tree() ?: return ""
            dir.findFile(name)?.delete()
            val doc = dir.createFile(mime, name) ?: return ""
            activity.contentResolver.openOutputStream(doc.uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            doc.name ?: name
        } catch (e: Exception) { "" }
    }

    @JavascriptInterface
    fun moveToChosenFolder(sourcePath: String, suggestedName: String): String {
        if (!allowed() || !isAppFile(sourcePath) || !isSimpleName(suggestedName)) return ""
        return try {
            val dir = tree() ?: return ""
            val src = File(sourcePath)
            if (!src.exists()) return ""
            val mime = when (src.extension.lowercase()) {
                "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"; "wav" -> "audio/wav"; "lrc" -> "text/plain"
                "opus" -> "audio/opus"; "webm" -> "audio/webm"; else -> "audio/mp4"
            }
            dir.findFile(suggestedName)?.delete()
            val newDoc = dir.createFile(mime, suggestedName) ?: return ""
            activity.contentResolver.openOutputStream(newDoc.uri).use { out -> FileInputStream(src).use { it.copyTo(out!!) } }
            src.delete()
            newDoc.name ?: suggestedName
        } catch (e: Exception) { "" }
    }

    @JavascriptInterface
    fun scrapeSpotifyPlaylist(playlistUrl: String) {
        if (!allowed()) return
        // Security: only Spotify pages are opened in the hidden reader.
        if (Uri.parse(playlistUrl).host?.endsWith("spotify.com") != true) return
        activity.runOnUiThread {
            val wv = WebView(activity)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            wv.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            wv.addJavascriptInterface(object {
                @JavascriptInterface fun onProgress(count: Int, total: Int) = activity.callMusicFlow("onScrapeProgress", JSONArray().put(count).put(total))
                @JavascriptInterface fun onDiag(info: String) = activity.callMusicFlow("onScrapeDiag", JSONArray().put(info))
                @JavascriptInterface fun onDone(json: String) {
                    activity.callMusicFlow("onScrapeComplete", JSONArray().put(json))
                    activity.runOnUiThread { (wv.parent as? android.view.ViewGroup)?.removeView(wv); wv.destroy() }
                }
            }, "AndroidScraper")
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Handler(Looper.getMainLooper()).postDelayed({ wv.evaluateJavascript(SpotifyScrape.JS, null) }, 2500)
                }
            }
            val metrics = activity.resources.displayMetrics
            val root = activity.findViewById<FrameLayout>(android.R.id.content)
            root.addView(wv, FrameLayout.LayoutParams(metrics.widthPixels, metrics.heightPixels))
            wv.translationX = (metrics.widthPixels * 3).toFloat()
            wv.loadUrl(playlistUrl)
        }
    }

    @JavascriptInterface
    fun startDownloadService() {
        val intent = Intent(activity, DownloadForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) activity.startForegroundService(intent) else activity.startService(intent)
        activity.runOnUiThread { activity.setDownloadTicks(true) }
    }

    @JavascriptInterface
    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = activity.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(activity.packageName)
    }

    @JavascriptInterface
    fun requestIgnoreBatteryOptimizations() {
        if (!allowed()) return
        activity.runOnUiThread {
            try {
                activity.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${activity.packageName}")))
            } catch (e: Exception) {
                try { activity.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { }
            }
        }
    }

    @JavascriptInterface
    @JvmOverloads
    fun updateDownloadProgress(text: String, percent: Int = -1) {
        val intent = Intent(activity, DownloadForegroundService::class.java)
            .putExtra(DownloadForegroundService.EXTRA_TEXT, text)
            .putExtra(DownloadForegroundService.EXTRA_PERCENT, percent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) activity.startForegroundService(intent) else activity.startService(intent)
    }

    @JavascriptInterface
    fun stopDownloadService() {
        activity.stopService(Intent(activity, DownloadForegroundService::class.java))
        activity.runOnUiThread { activity.setDownloadTicks(false) }
        activity.rescanSoon()
    }
}
