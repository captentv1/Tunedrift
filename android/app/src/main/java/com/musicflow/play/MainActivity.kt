package com.musicflow.play

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
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
        const val EXTRA_ROUTE = "route"
        @Volatile private var serverStarted = false
        @Volatile var localPage = true
        private const val ALLOWED_UPDATE_URL = "https://github.com/captentv1/musicflow-play/releases/download/"

        val API_TOKEN: String by lazy {
            val b = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            android.util.Base64.encodeToString(
                b, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
        }

        fun callPage(js: String) = WebHost.eval(js)

        fun hasAllFilesAccess(ctx: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
    }

    private lateinit var webView: WebView
    private lateinit var loadingView: LinearLayout
    private var hadAllFiles = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { rescanFromPage() }

    private val folderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val folder = if (uri != null) treeUriToPath(uri) ?: "" else null
            if (folder != null) callPage("window.MFP_folderPicked && MFP_folderPicked(${org.json.JSONObject.quote(folder)})")
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

    private var fileCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    fun pickFolder() {
        try { folderLauncher.launch(null) } catch (e: Exception) {
            callPage("window.MFP_folderPicked && MFP_folderPicked('')")
        }
    }

    private fun rescanFromPage() = callPage("window.MFP_rescan && MFP_rescan()")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemInsets()
        if (intent?.getBooleanExtra(UpdateManager.EXTRA_INSTALLER, false) == true) window.decorView.post { installDownloadedApk() }
        loadingView = findViewById(R.id.loading)
        if (BuildConfig.WEB_DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        try { com.musicflow.app.SpotifySearch.init(this) } catch (e: Exception) { }
        SpotifyBot.init(this)
        Thread { try { UpdateManager.forgetOldDownload(applicationContext) } catch (e: Exception) { } }.start()
        if (BuildConfig.WEB_DEBUG) intent?.getStringExtra("test_spotify_base")?.let { SpotifyBot.useTestSite(it) }

        PlaybackService.commandSink = { command, value ->
            if (command.matches(Regex("[a-z]+"))) callPage("window.MFP_cmd && MFP_cmd('$command', $value)")
        }
        hadAllFiles = hasAllFilesAccess(this)
        if (!serverStarted) {
            serverStarted = true
            Thread {
                if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
                startPythonServer()
            }.start()
        }
        attachPage()
        askPermissions()
    }

    /** Puts the page in this window: the one kept from before (music still playing) or a new one. */
    private fun attachPage() {
        val (w, fresh) = WebHost.obtain(this)
        webView = w
        findViewById<FrameLayout>(R.id.page_holder).addView(
            w, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        setClients(w)
        if (!fresh) {
            loadingView.visibility = View.GONE
            if (insetsJs.isNotEmpty()) callPage(insetsJs)
            // The new window starts with light status bar icons: the page applies its theme again.
            callPage("window.MFP_resume && MFP_resume()")
            routeFrom(intent)
            return
        }
        w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.addJavascriptInterface(PageBridge(applicationContext), "MFPNative")
        Thread { waitForServerThenLoad() }.start()
    }

    private fun setClients(w: WebView) {
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                Log.i("MFPConsole", message?.message() ?: "")
                return true
            }

            override fun onShowFileChooser(
                view: WebView?, callback: android.webkit.ValueCallback<Array<Uri>>?, params: FileChooserParams?
            ): Boolean {
                val act = WebHost.activity ?: return false
                act.fileCallback?.onReceiveValue(null)
                act.fileCallback = callback
                return try { act.fileLauncher.launch("*/*"); true } catch (e: Exception) { act.fileCallback = null; false }
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (isLocal(url.toString())) return false
                openExternal(url.toString())
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                localPage = isLocal(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                WebHost.activity?.let { act ->
                    act.loadingView.visibility = View.GONE
                    if (act.insetsJs.isNotEmpty() && isLocal(url)) view?.evaluateJavascript(act.insetsJs, null)
                    act.routeFrom(act.intent)
                }
            }

            // Android can end the page's renderer to get memory back (a phone busy downloading and converting):
            // without this the whole app was closed ("it crashes when I download and play"). Now the page loads again.
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                Log.w("MFP", "page renderer gone (crash=${detail?.didCrash()})")
                if (view !== WebHost.webView) return true
                val ctx = view?.context?.applicationContext
                WebHost.release()
                PlaybackService.playing = false
                val act = WebHost.activity
                if (act != null) {
                    act.loadingView.visibility = View.VISIBLE
                    act.attachPage()
                } else if (ctx != null && PlaybackService.running) {
                    try { ctx.startService(Intent(ctx, PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP)) } catch (e: Exception) { }
                }
                return true
            }
        }
    }

    private fun routeFrom(i: Intent?) {
        // Emulator test build only: removes the task as swiping it away from the recent apps does.
        if (BuildConfig.WEB_DEBUG && i?.getBooleanExtra("test_remove_task", false) == true) {
            i.removeExtra("test_remove_task")
            window.decorView.postDelayed({ finishAndRemoveTask() }, 500)
            return
        }
        val r = i?.getStringExtra(EXTRA_ROUTE) ?: return
        i.removeExtra(EXTRA_ROUTE)
        if (r.matches(Regex("[a-z]+"))) callPage("location.hash = '$r'")
    }

    fun openExternal(url: String) {
        if (Uri.parse(url).scheme?.lowercase() !in setOf("https", "http")) return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
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

    fun askPermissions() {
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

    private fun startPythonServer() {
        try {
            val storage = Environment.getExternalStorageDirectory()
            val folders = listOf(File(storage, "Music"), File(storage, "Download"))
                .joinToString(File.pathSeparator) { it.absolutePath }
            val roots = listOf(storage.absolutePath, "/storage").joinToString(File.pathSeparator)
            val dataDir = File(filesDir, "data").apply { mkdirs() }
            // Where songs go until a root folder is chosen: Music/MusicFlow (seen by every music player) when the
            // app may write there, else the app's own folder.
            val mfDest = if (hasAllFilesAccess(this)) File(storage, "Music/MusicFlow")
            else (getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(filesDir, "Music"))
            Python.getInstance().getModule("mfp_mobile").callAttr(
                "start", dataDir.absolutePath, API_TOKEN, folders, roots,
                mfDest.absolutePath, File(filesDir, "musicflow-config.json").absolutePath
            )
        } catch (e: Exception) {
            Log.e("MFP", "server did not start", e)
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
        runOnUiThread { WebHost.webView?.loadUrl(PAGE_URL) }
    }

    private var pendingApk: File? = null
    @Volatile var inForeground = false

    /** Downloads the update (or, while one is under way, only shows its progress again), then installs it. */
    fun downloadUpdate(url: String) {
        val ctx = applicationContext
        UpdateManager.progress?.let { p -> callPage("window.MFP_updateProgress && MFP_updateProgress($p)"); return }
        Thread {
            try {
                callPage("window.MFP_updateProgress && MFP_updateProgress(-1)")
                val started = UpdateManager.download(ctx, url) { p -> callPage("window.MFP_updateProgress && MFP_updateProgress($p)") }
                if (!started) return@Thread
                val apk = UpdateManager.file(ctx)
                UpdateManager.verify(ctx, apk)
                callPage("window.MFP_updateProgress && MFP_updateProgress(100)")
                val act = WebHost.activity
                if (act == null || !act.inForeground) {
                    UpdateManager.notifyReady(ctx, ctx.packageManager.getPackageArchiveInfo(apk.path, 0)?.versionName ?: "")
                    return@Thread
                }
                act.runOnUiThread { act.pendingApk = apk; act.installApk() }
            } catch (e: Exception) {
                callPage("window.MFP_updateError && MFP_updateError(${org.json.JSONObject.quote(e.message ?: e.toString())})")
            }
        }.start()
    }

    fun installDownloadedApk() {
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
        setIntent(intent)
        if (intent.getBooleanExtra(UpdateManager.EXTRA_INSTALLER, false)) installDownloadedApk()
        routeFrom(intent)
    }

    override fun onPause() {
        super.onPause()
        inForeground = false
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        if (pendingApk != null) {
            if (packageManager.canRequestPackageInstalls()) installApk()
            else { pendingApk = null; callPage("window.MFP_updateError && MFP_updateError('permission to install apps refused')") }
        }
        val now = hasAllFilesAccess(this)
        if (now != hadAllFiles) {
            hadAllFiles = now
            rescanFromPage()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (SpotifyBot.closeLogin()) {
                callPage("window.MFP_spotifyDone && MFP_spotifyDone(false)")
                return true
            }
            if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        // The window closes (e.g. swiped away from the recent apps): while a song plays, the page stays alive in
        // WebHost with the playback service, so the music goes on. Otherwise the page is freed.
        val playing = PlaybackService.playing && PlaybackService.running
        SpotifyBot.closeLogin()
        SpotifyBot.detach()
        WebHost.detach(this, keep = playing || isChangingConfigurations)
        if (isFinishing && !playing && PlaybackService.running) {
            try { startService(Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP)) } catch (e: Exception) { }
        }
        super.onDestroy()
    }
}

/** What the player's page asks the phone for. Works with or without a window (music playing after a swipe). */
class PageBridge(private val app: Context) {

    private fun allowed() = MainActivity.localPage

    private fun updateService() {
        try {
            val intent = Intent(app, PlaybackService::class.java).setAction(PlaybackService.ACTION_UPDATE)
            if (PlaybackService.running) app.startService(intent)
            else if (PlaybackService.playing) ContextCompat.startForegroundService(app, intent.putExtra(PlaybackService.EXTRA_FOREGROUND, true))
        } catch (e: Exception) { }
    }

    // Security: the token is only given to the app's own page.
    @JavascriptInterface
    fun token(): String = if (allowed()) MainActivity.API_TOKEN else ""

    @JavascriptInterface
    fun appVersion(): String = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""

    @JavascriptInterface
    fun nowPlaying(title: String?, artist: String?, album: String?, coverUrl: String?, durationMs: Double) {
        if (!allowed()) return
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
        if (!allowed()) return
        PlaybackService.playing = playing
        PlaybackService.positionMs = positionMs.toLong()
        if (durationMs > 0) PlaybackService.durationMs = durationMs.toLong()
        updateService()
    }

    @JavascriptInterface
    fun modes(shuffle: Boolean, repeat: String?) {
        if (!allowed()) return
        PlaybackService.shuffle = shuffle
        PlaybackService.repeat = repeat ?: "off"
        updateService()
    }

    @JavascriptInterface
    fun stop() {
        if (!allowed()) return
        PlaybackService.playing = false
        if (PlaybackService.running) {
            try { app.startService(Intent(app, PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP)) } catch (e: Exception) { }
        }
    }

    @JavascriptInterface
    fun installUpdate(apkUrl: String) {
        if (!allowed()) return
        // Security: updates only from this repository's GitHub releases.
        if (!apkUrl.startsWith("https://github.com/captentv1/musicflow-play/releases/download/") || !apkUrl.endsWith(".apk")) {
            MainActivity.callPage("window.MFP_updateError && MFP_updateError('download address refused')"); return
        }
        val a = WebHost.activity
        if (a == null) { MainActivity.callPage("window.MFP_updateError && MFP_updateError('open the app and try again')"); return }
        a.downloadUpdate(apkUrl)
    }

    @JavascriptInterface
    fun updateReady(): String = if (allowed()) UpdateManager.readyVersion(app) else ""

    @JavascriptInterface
    fun installDownloadedUpdate() {
        if (!allowed()) return
        WebHost.activity?.let { a -> a.runOnUiThread { a.installDownloadedApk() } }
    }

    /** Downloads, imports, lyrics synchronization and the albums fix go on with the app closed: a notification shows them. */
    @JavascriptInterface
    fun backgroundWork() {
        if (!allowed()) return
        synchronized(this) {
            if (backgroundWorking) return
            backgroundWorking = true
        }
        Thread {
            try {
                val jobs = listOf("downloads" to "Downloading", "lyrics_sync" to "Lyrics synchronization", "album_fix" to "Albums fix")
                var shown = ""
                while (true) {
                    val job = jobs.firstOrNull { (m, _) ->
                        try { Python.getInstance().getModule(m)["STATUS"]!!.callAttr("get", "running").toBoolean() } catch (e: Exception) { false }
                    } ?: break
                    val status = Python.getInstance().getModule(job.first)["STATUS"]!!
                    val done = status.callAttr("get", "done").toInt()
                    val total = maxOf(1, status.callAttr("get", "total").toInt())
                    val current = try { status.callAttr("get", "current")?.toString() ?: "" } catch (e: Exception) { "" }
                    val text = "${job.second}: $done / $total" + if (current.isNotEmpty() && current != "None") " · $current" else ""
                    if (text != shown) {
                        shown = text
                        WorkService.update(app, text, done * 100 / total)
                    }
                    Thread.sleep(3000)
                }
            } catch (e: Exception) {
                Log.e("MFP", "background work notification", e)
            } finally {
                WorkService.stop(app)
                backgroundWorking = false
            }
        }.start()
    }

    @JavascriptInterface
    fun setLightStatusBar(light: Boolean) {
        val a = WebHost.activity ?: return
        a.runOnUiThread {
            WindowCompat.getInsetsController(a.window, a.window.decorView).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }

    @JavascriptInterface
    fun pickFolder() {
        if (!allowed()) return
        val a = WebHost.activity ?: return MainActivity.callPage("window.MFP_folderPicked && MFP_folderPicked('')")
        a.runOnUiThread { a.pickFolder() }
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        if (!allowed()) return
        val a = WebHost.activity ?: return
        a.runOnUiThread { a.openExternal(url) }
    }

    @JavascriptInterface
    fun spotifyLogin() {
        if (!allowed()) return
        val a = WebHost.activity ?: return
        a.runOnUiThread { SpotifyBot.login(a) }
    }

    @JavascriptInterface
    fun spotifyLogout() {
        if (allowed()) SpotifyBot.logout()
    }

    @JavascriptInterface
    fun hasAllFiles(): Boolean = MainActivity.hasAllFilesAccess(app)

    @JavascriptInterface
    fun requestAllFiles() {
        if (!allowed()) return
        val a = WebHost.activity ?: return
        a.runOnUiThread {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    a.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${a.packageName}")))
                } catch (e: Exception) {
                    try { a.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (e2: Exception) { }
                }
            } else {
                a.askPermissions()
            }
        }
    }

    companion object {
        @Volatile private var backgroundWorking = false
    }
}
