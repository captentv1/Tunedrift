package com.musicflow.play

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.net.Socket

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SERVER_PORT = 5091
        const val PAGE_URL = "http://127.0.0.1:5091/"
        @Volatile private var serverStarted = false

        val API_TOKEN: String by lazy {
            val b = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            android.util.Base64.encodeToString(
                b, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
        }
    }

    private lateinit var webView: WebView
    private lateinit var loadingView: LinearLayout
    @Volatile private var isLocalPage = true
    private var hadAllFiles = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { rescanFromPage() }

    private val folderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val folder = treeUriToPath(uri) ?: ""
                callPage("window.MFP_folderPicked && MFP_folderPicked(${org.json.JSONObject.quote(folder)})")
            }
        }

    private fun treeUriToPath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = try { DocumentsContract.getTreeDocumentId(uri) } catch (e: Exception) { return null }
        val parts = id.split(":", limit = 2)
        val volume = parts[0]
        val inside = parts.getOrElse(1) { "" }.trim('/')
        val base = if (volume.equals("primary", ignoreCase = true)) Environment.getExternalStorageDirectory().absolutePath
        else "/storage/$volume"
        val folder = File(if (inside.isEmpty()) base else "$base/$inside")
        return if (folder.isDirectory) folder.absolutePath else null
    }

    private fun isLocal(url: String?) = url?.startsWith(PAGE_URL) == true

    private fun callPage(js: String) {
        runOnUiThread {
            try { webView.evaluateJavascript(js, null) } catch (e: Exception) { }
        }
    }

    private fun rescanFromPage() = callPage("window.MFP_rescan && MFP_rescan()")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemInsets()

        webView = findViewById(R.id.webview)
        loadingView = findViewById(R.id.loading)
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        if (BuildConfig.WEB_DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.addJavascriptInterface(Bridge(), "MFPNative")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                Log.i("MFPConsole", message?.message() ?: "")
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (isLocal(url.toString())) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                isLocalPage = isLocal(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loadingView.visibility = View.GONE
            }
        }

        PlaybackService.commandSink = { command, value ->
            if (command.matches(Regex("[a-z]+"))) callPage("window.MFP_cmd && MFP_cmd('$command', $value)")
        }

        hadAllFiles = hasAllFilesAccess()
        if (!serverStarted) {
            serverStarted = true
            Thread {
                if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
                startPythonServer()
            }.start()
        }
        Thread { waitForServerThenLoad() }.start()
        askPermissions()
    }

    private fun applySystemInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val rootView = findViewById<FrameLayout>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
    }

    private fun askPermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted.add(Manifest.permission.READ_MEDIA_AUDIO)
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            wanted.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startPythonServer() {
        try {
            val storage = Environment.getExternalStorageDirectory()
            val folders = listOf(File(storage, "Music"), File(storage, "Download"))
                .joinToString(File.pathSeparator) { it.absolutePath }
            val roots = listOf(storage.absolutePath, "/storage").joinToString(File.pathSeparator)
            val dataDir = File(filesDir, "data").apply { mkdirs() }
            Python.getInstance().getModule("mobile_app")
                .callAttr("start", dataDir.absolutePath, API_TOKEN, folders, roots)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun waitForServerThenLoad() {
        var attempts = 0
        while (attempts < 400) {
            try {
                Socket("127.0.0.1", SERVER_PORT).close()
                break
            } catch (e: Exception) {
                attempts++
                Thread.sleep(150)
            }
        }
        runOnUiThread { webView.loadUrl(PAGE_URL) }
    }

    override fun onResume() {
        super.onResume()
        val now = hasAllFilesAccess()
        if (now != hadAllFiles) {
            hadAllFiles = now
            rescanFromPage()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        if (isFinishing) {
            PlaybackService.commandSink = null
            if (PlaybackService.running) {
                try { startService(Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP)) } catch (e: Exception) { }
            }
        }
        super.onDestroy()
    }

    private fun updateService() {
        try {
            val intent = Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_UPDATE)
            if (PlaybackService.running) startService(intent)
            else if (PlaybackService.playing) ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) { }
    }

    inner class Bridge {

        // Security: the token is only given to the app's own page.
        @JavascriptInterface
        fun token(): String = if (isLocalPage) API_TOKEN else ""

        @JavascriptInterface
        fun appVersion(): String = packageManager.getPackageInfo(packageName, 0).versionName ?: ""

        @JavascriptInterface
        fun nowPlaying(title: String?, artist: String?, album: String?, coverUrl: String?, durationMs: Double) {
            if (!isLocalPage) return
            PlaybackService.title = title ?: ""
            PlaybackService.artist = artist ?: ""
            PlaybackService.album = album ?: ""
            PlaybackService.coverUrl = coverUrl ?: ""
            PlaybackService.durationMs = durationMs.toLong()
            PlaybackService.positionMs = 0
            updateService()
        }

        @JavascriptInterface
        fun playback(playing: Boolean, positionMs: Double, durationMs: Double) {
            if (!isLocalPage) return
            PlaybackService.playing = playing
            PlaybackService.positionMs = positionMs.toLong()
            if (durationMs > 0) PlaybackService.durationMs = durationMs.toLong()
            updateService()
        }

        @JavascriptInterface
        fun modes(shuffle: Boolean, repeat: String?) {
            if (!isLocalPage) return
            PlaybackService.shuffle = shuffle
            PlaybackService.repeat = repeat ?: "off"
            updateService()
        }

        @JavascriptInterface
        fun stop() {
            if (!isLocalPage) return
            PlaybackService.playing = false
            if (PlaybackService.running) {
                try { startService(Intent(this@MainActivity, PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP)) } catch (e: Exception) { }
            }
        }

        @JavascriptInterface
        fun setLightStatusBar(light: Boolean) {
            runOnUiThread {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
        }

        @JavascriptInterface
        fun pickFolder() {
            if (!isLocalPage) return
            runOnUiThread {
                try { folderLauncher.launch(null) } catch (e: Exception) {
                    callPage("window.MFP_folderPicked && MFP_folderPicked('')")
                }
            }
        }

        @JavascriptInterface
        fun hasAllFiles(): Boolean = hasAllFilesAccess()

        @JavascriptInterface
        fun requestAllFiles() {
            if (!isLocalPage) return
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (e2: Exception) { }
                    }
                } else {
                    askPermissions()
                }
            }
        }
    }
}
