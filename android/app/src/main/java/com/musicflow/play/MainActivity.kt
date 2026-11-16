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
        @Volatile private var musicFlowStarted = false
        @Volatile var updateFollowedByPage = false
        private const val ALLOWED_UPDATE_URL = "https://github.com/captentv1/musicflow-play/releases/download/"
        private const val MUSICFLOW_URL = "http://127.0.0.1:5090/"
        private val LOGIN_HOSTS = setOf("accounts.spotify.com", "accounts.google.com")

        /** MusicFlow's own token (Download tab), given only to the player's page, which passes it to MusicFlow's frame. */
        val MF_TOKEN: String by lazy {
            val b = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            android.util.Base64.encodeToString(
                b, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
        }

        val API_TOKEN: String by lazy {
            val b = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            android.util.Base64.encodeToString(
                b, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
        }
    }

    private lateinit var webView: WebView
    private lateinit var loadingView: LinearLayout
    @Volatile var isLocalPage = true
        private set
    private lateinit var musicFlow: MusicFlowBridge
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

    // ---- MusicFlow (Download tab): what its page asks the phone for ----

    private val mfFolderLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data
            if (result.resultCode == RESULT_OK && uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) { }
                getSharedPreferences("musicflow", MODE_PRIVATE).edit().putString(MusicFlowBridge.PREF_TREE_URI, uri.toString()).apply()
                callMusicFlow("onFolderChosen", org.json.JSONArray().put(musicFlow.chosenFolderLabel()))
                // The songs MusicFlow saves there also appear in the player's library.
                treeUriToPath(uri)?.let { callPage("window.MFP_folderPicked && MFP_folderPicked(${org.json.JSONObject.quote(it)})") }
            }
        }

    fun chooseMusicFlowFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        try { mfFolderLauncher.launch(intent) } catch (e: Exception) { }
    }

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: ""
        callMusicFlow("MF_voice", org.json.JSONArray().put(text))
    }

    fun startVoiceSearch(): Boolean {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say the title or the artist")
        if (intent.resolveActivity(packageManager) == null) return false
        runOnUiThread {
            try { voiceLauncher.launch(intent) } catch (e: Exception) { callMusicFlow("MF_voice", org.json.JSONArray().put("")) }
        }
        return true
    }

    private var fileCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    /** Calls one of MusicFlow's page functions, in its frame (through the player page). */
    fun callMusicFlow(fn: String, args: org.json.JSONArray) =
        callPage("window.MFP_toMusicFlow && MFP_toMusicFlow(${org.json.JSONObject.quote(fn)}, $args)")

    private val tickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var ticking = false
    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            callMusicFlow("MF_tic", org.json.JSONArray())
            tickHandler.postDelayed(this, 3000)
        }
    }

    /** While MusicFlow downloads with the screen off, its page is woken every 3 s (as in the MusicFlow app). */
    fun setDownloadTicks(on: Boolean) {
        if (on && !ticking) { ticking = true; tickHandler.post(tick) }
        else if (!on) { ticking = false; tickHandler.removeCallbacks(tick) }
    }

    fun rescanSoon() = callPage("setTimeout(() => window.MFP_rescan && MFP_rescan(), 2000)")

    private fun musicFlowDir(): File =
        (getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(filesDir, "Music")).apply { mkdirs() }

    private fun startMusicFlow() {
        try {
            Python.getInstance().getModule("mfp_mobile").callAttr(
                "start_musicflow", musicFlowDir().absolutePath, File(filesDir, "musicflow-config.json").absolutePath, MF_TOKEN, BuildConfig.WEB_DEBUG
            )
        } catch (e: Exception) {
            Log.e("MFP", "MusicFlow (Download tab) did not start", e)
        }
    }

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
        if (intent?.getBooleanExtra(UpdateManager.EXTRA_INSTALLER, false) == true) window.decorView.post { installDownloadedApk() }

        webView = findViewById(R.id.webview)
        loadingView = findViewById(R.id.loading)
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        if (BuildConfig.WEB_DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.addJavascriptInterface(Bridge(), "MFPNative")
        musicFlow = MusicFlowBridge(this)
        webView.addJavascriptInterface(musicFlow, "Android")
        try { com.musicflow.app.SpotifySearch.init(this) } catch (e: Exception) { }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                Log.i("MFPConsole", message?.message() ?: "")
                return true
            }

            override fun onShowFileChooser(
                view: WebView?, callback: android.webkit.ValueCallback<Array<Uri>>?, params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try { fileLauncher.launch("*/*"); true } catch (e: Exception) { fileCallback = null; false }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (isLocal(url.toString())) return false
                // MusicFlow's frame, and the Spotify / Google sign-in pages it opens.
                if (request?.isForMainFrame == false && (url.toString().startsWith(MUSICFLOW_URL) || url.host in LOGIN_HOSTS)) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                isLocalPage = isLocal(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loadingView.visibility = View.GONE
                if (insetsJs.isNotEmpty() && isLocal(url)) view?.evaluateJavascript(insetsJs, null)
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
        Thread {
            waitForServerThenLoad()
            if (!musicFlowStarted) { musicFlowStarted = true; startMusicFlow() }
        }.start()
        askPermissions()
    }

    private var insetsJs = ""

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
            val typing = keyboard.bottom > bars.bottom
            view.setPadding(bars.left, 0, bars.right, if (typing) keyboard.bottom else 0)
            val d = resources.displayMetrics.density
            insetsJs = "document.documentElement.style.setProperty('--sa-t','${bars.top / d}px');" +
                "document.documentElement.style.setProperty('--safe-b','${if (typing) 0f else bars.bottom / d}px')"
            if (::webView.isInitialized) callPage(insetsJs)
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
            Python.getInstance().getModule("mfp_mobile")
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

    private var pendingApk: File? = null
    @Volatile private var inForeground = false
    @Volatile private var backgroundWorking = false

    private fun followUpdate() {
        if (updateFollowedByPage) return
        updateFollowedByPage = true
        Thread {
            try {
                var last = -2
                while (true) {
                    val state = UpdateManager.state(this) ?: throw Exception("download cancelled")
                    when (state.first) {
                        android.app.DownloadManager.STATUS_SUCCESSFUL -> {
                            UpdateManager.finished(this)
                            val apk = UpdateManager.file(this)
                            UpdateManager.verify(this, apk)
                            callPage("window.MFP_updateProgress && MFP_updateProgress(100)")
                            if (!inForeground) {
                                UpdateManager.notifyReady(this, packageManager.getPackageArchiveInfo(apk.path, 0)?.versionName ?: "")
                                return@Thread
                            }
                            pendingApk = apk
                            runOnUiThread { installApk() }
                            return@Thread
                        }
                        android.app.DownloadManager.STATUS_FAILED -> { UpdateManager.finished(this); throw Exception("download failed") }
                        else -> if (state.second != last) {
                            last = state.second
                            callPage("window.MFP_updateProgress && MFP_updateProgress(${minOf(state.second, 99)})")
                        }
                    }
                    Thread.sleep(500)
                }
            } catch (e: Exception) {
                callPage("window.MFP_updateError && MFP_updateError(${org.json.JSONObject.quote(e.message ?: e.toString())})")
            } finally {
                updateFollowedByPage = false
            }
        }.start()
    }

    private fun installDownloadedApk() {
        UpdateManager.clearNotification(this)
        val apk = UpdateManager.file(this)
        try { UpdateManager.verify(this, apk) } catch (e: Exception) {
            callPage("window.MFP_updateError && MFP_updateError(${org.json.JSONObject.quote(e.message ?: "")})"); return
        }
        pendingApk = apk
        installApk()
    }

    private fun installApk() {
        val apk = pendingApk ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            callPage("window.MFP_updateProgress && MFP_updateProgress(-2)")
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (e: Exception) { }
            return
        }
        pendingApk = null
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fichiers", apk)
        startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(UpdateManager.EXTRA_INSTALLER, false)) installDownloadedApk()
    }

    override fun onPause() {
        super.onPause()
        inForeground = false
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        val upd = UpdateManager.state(this)
        if (upd != null && upd.first != android.app.DownloadManager.STATUS_FAILED) followUpdate()
        if (pendingApk != null) {
            if (packageManager.canRequestPackageInstalls()) installApk()
            else { pendingApk = null; callPage("window.MFP_updateError && MFP_updateError('permission to install apps refused')") }
        }
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
        fun installUpdate(apkUrl: String) {
            if (!isLocalPage) return
            // Security: updates only from this repository's GitHub releases.
            if (!apkUrl.startsWith(ALLOWED_UPDATE_URL) || !apkUrl.endsWith(".apk")) {
                callPage("window.MFP_updateError && MFP_updateError('download address refused')"); return
            }
            UpdateManager.startDownload(this@MainActivity, apkUrl)
            followUpdate()
        }

        @JavascriptInterface
        fun updateReady(): String = if (isLocalPage) UpdateManager.readyVersion(this@MainActivity) else ""

        @JavascriptInterface
        fun installDownloadedUpdate() {
            if (!isLocalPage) return
            runOnUiThread { installDownloadedApk() }
        }

        /** Lyrics synchronization keeps running with the screen off or the app closed: a notification shows it. */
        @JavascriptInterface
        fun backgroundWork() {
            if (!isLocalPage || backgroundWorking) return
            backgroundWorking = true
            Thread {
                val service = Intent(this@MainActivity, com.musicflow.app.DownloadForegroundService::class.java)
                try {
                    // Lyrics synchronization and albums fix go on with the app closed (foreground service).
                    val jobs = listOf("lyrics_sync" to "Lyrics synchronization", "album_fix" to "Albums fix")
                    while (true) {
                        val job = jobs.firstOrNull { (m, _) ->
                            try { Python.getInstance().getModule(m)["STATUS"]!!.callAttr("get", "running").toBoolean() } catch (e: Exception) { false }
                        } ?: break
                        val status = Python.getInstance().getModule(job.first)["STATUS"]!!
                        val done = status.callAttr("get", "done").toInt()
                        val total = maxOf(1, status.callAttr("get", "total").toInt())
                        ContextCompat.startForegroundService(this@MainActivity, Intent(service)
                            .putExtra(com.musicflow.app.DownloadForegroundService.EXTRA_TEXT, "${job.second}: $done / $total")
                            .putExtra(com.musicflow.app.DownloadForegroundService.EXTRA_PERCENT, done * 100 / total))
                        Thread.sleep(2000)
                    }
                } catch (e: Exception) {
                    Log.e("MFP", "lyrics synchronization notification", e)
                } finally {
                    try { stopService(service) } catch (e: Exception) { }
                    backgroundWorking = false
                }
            }.start()
        }

        @JavascriptInterface
        fun musicflowToken(): String = if (isLocalPage) MF_TOKEN else ""

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
