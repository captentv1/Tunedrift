package com.musicflow.play

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spotify with the person's own account, without any developer key.
 *
 * - login(): Spotify's own sign-in page in the app. Once signed in, Spotify's session cookie stays in the app.
 * - A hidden page of Spotify's web player (the "bot") then opens with that session. The web player asks Spotify's
 *   servers with an access token of the person: the bot reads it from the requests and the server lists the
 *   playlists and their songs with it (Spotify's Web API).
 * - If Spotify refuses that, the bot reads the same lists from the web player's page itself (library and songs,
 *   scrolled to the end).
 * Python (server/importer.py) calls the @JvmStatic functions from its own threads; they wait for the page.
 */
object SpotifyBot {
    private const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val TOKEN_AGE_MS = 40 * 60 * 1000L
    private const val IDLE_MS = 3 * 60 * 1000L

    @Volatile var web = "https://open.spotify.com"
        private set
    @Volatile var api = "https://api.spotify.com/v1"
        private set
    @Volatile private var loginPage = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
    @Volatile private var test = false

    private val ui = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var bot: WebView? = null
    private var overlay: View? = null
    private val lock = Any()
    @Volatile private var bearer = ""
    @Volatile private var tokenAt = 0L
    private val release = Runnable { releaseBot() }

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    /** Emulator test build only: a fake Spotify on the test computer. */
    fun useTestSite(base: String) {
        web = base.trimEnd('/'); api = "$web/v1"; loginPage = "$web/login"; test = true
    }

    // ------------------------------------------------------------------ account

    @JvmStatic
    fun connected(): Boolean = (CookieManager.getInstance().getCookie(web) ?: "").split(';').any { it.trim().startsWith("sp_dc=") }

    @JvmStatic
    fun apiBase(): String = api

    @JvmStatic
    fun webBase(): String = web

    /** Spotify's sign-in page over the app; closes by itself once signed in. */
    @SuppressLint("SetJavaScriptEnabled")
    fun login(activity: MainActivity) {
        if (overlay != null) return
        val root = activity.findViewById<FrameLayout>(android.R.id.content)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            isClickable = true
        }
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 8), dp(activity, 8))
        }
        val title = TextView(activity).apply {
            text = "Spotify"
            setTextColor(Color.WHITE)
            textSize = 18f
        }
        val close = Button(activity).apply { text = "Close" }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(close)
        val w = WebView(activity)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        var finished = false
        fun check(url: String?) {
            if (finished || !connected()) return
            val u = url ?: ""
            if (u.startsWith(web) || (!test && !u.contains("accounts.spotify.com") && u.contains("spotify.com"))) {
                finished = true
                CookieManager.getInstance().flush()
                synchronized(lock) { bearer = ""; tokenAt = 0L }
                closeLogin()
                WebHost.eval("window.MFP_spotifyDone && MFP_spotifyDone(true)")
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val s = request?.url?.scheme?.lowercase()
                return s != "https" && s != "http"  // "open in the Spotify app" links: stay here
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) = check(url)
            override fun onPageFinished(view: WebView?, url: String?) = check(url)
        }
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        close.setOnClickListener {
            closeLogin()
            WebHost.eval("window.MFP_spotifyDone && MFP_spotifyDone(false)")
        }
        root.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        box.requestApplyInsets()
        overlay = box
        w.loadUrl(loginPage)
    }

    /** True if the sign-in page was open (the back button closes it). */
    fun closeLogin(): Boolean {
        val box = overlay ?: return false
        overlay = null
        (box.parent as? ViewGroup)?.removeView(box)
        val w = (box as ViewGroup).getChildAt(1) as? WebView
        try { w?.stopLoading(); w?.destroy() } catch (e: Exception) { }
        return true
    }

    @JvmStatic
    fun logout() {
        val cm = CookieManager.getInstance()
        for (site in listOf(web, "https://accounts.spotify.com", "https://www.spotify.com", "https://spotify.com")) {
            val names = (cm.getCookie(site) ?: "").split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
            for (n in names) {
                cm.setCookie(site, "$n=; Max-Age=0; Path=/")
                if (!test) cm.setCookie(site, "$n=; Max-Age=0; Path=/; Domain=.spotify.com")
            }
        }
        cm.flush()
        synchronized(lock) { bearer = ""; tokenAt = 0L }
        ui.post { releaseBot() }
    }

    // ------------------------------------------------------------------ the hidden web player

    @SuppressLint("SetJavaScriptEnabled")
    private fun botView(): WebView {
        bot?.let { return it }
        val w = WebView(appContext!!)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.userAgentString = UA_DESKTOP
        w.settings.blockNetworkImage = true
        w.settings.mediaPlaybackRequiresUserGesture = true
        w.settings.useWideViewPort = true
        w.settings.loadWithOverviewMode = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val r = request ?: return null
                val h = r.requestHeaders ?: return null
                val auth = h["authorization"] ?: h["Authorization"] ?: return null
                val host = r.url?.host ?: ""
                if (auth.startsWith("Bearer ") && (test || host.endsWith("spotify.com"))) {
                    bearer = auth.substring(7).trim()
                    tokenAt = System.currentTimeMillis()
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val s = request?.url?.scheme?.lowercase()
                return s != "https" && s != "http"
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                view?.evaluateJavascript(JS_WIDE, null)
            }
        }
        // Off screen in the window when there is one (the web player then runs as in a browser), else on its own.
        w.translationX = -5000f
        val a = WebHost.activity
        if (a != null) {
            try {
                a.findViewById<FrameLayout>(android.R.id.content).addView(w, 0, FrameLayout.LayoutParams(1280, 2400))
            } catch (e: Exception) { w.layout(0, 0, 1280, 2400) }
        } else {
            w.layout(0, 0, 1280, 2400)
        }
        bot = w
        return w
    }

    private fun releaseBot() {
        val w = bot ?: return
        bot = null
        (w.parent as? ViewGroup)?.removeView(w)
        try { w.stopLoading(); w.destroy() } catch (e: Exception) { }
    }

    /** Called when the window closes: the hidden page must not keep it. */
    fun detach() {
        ui.post { if ((bot?.parent) != null) releaseBot() }
    }

    private fun load(url: String) {
        val done = CountDownLatch(1)
        ui.post {
            try {
                ui.removeCallbacks(release)
                botView().loadUrl(url)
            } catch (e: Exception) { }
            done.countDown()
        }
        done.await(5, TimeUnit.SECONDS)
    }

    private fun idleLater() {
        ui.post { ui.removeCallbacks(release); ui.postDelayed(release, IDLE_MS) }
    }

    private fun js(script: String): String {
        val done = CountDownLatch(1)
        var out = ""
        ui.post {
            val w = bot
            if (w == null) { done.countDown(); return@post }
            try {
                w.evaluateJavascript(script) { v -> out = v ?: ""; done.countDown() }
            } catch (e: Exception) { done.countDown() }
        }
        done.await(5, TimeUnit.SECONDS)
        // evaluateJavascript gives the JSON of the value: a string comes back quoted.
        return try { if (out.startsWith("\"")) org.json.JSONTokener(out).nextValue() as String else out } catch (e: Exception) { "" }
    }

    /** The person's access token, as the web player uses it ("" if not signed in). */
    @JvmStatic
    fun token(timeoutMs: Long): String {
        if (appContext == null || !connected()) return ""
        synchronized(lock) {
            if (bearer.isNotEmpty() && System.currentTimeMillis() - tokenAt < TOKEN_AGE_MS) return bearer
            bearer = ""
            load("$web/collection/tracks")
            val end = System.currentTimeMillis() + timeoutMs
            while (bearer.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(300)
            idleLater()
            return bearer
        }
    }

    /** The playlists of the person's library, read from the web player's page: [{"id", "name"}]. */
    @JvmStatic
    fun playlists(timeoutMs: Long): String {
        if (appContext == null || !connected()) return "[]"
        synchronized(lock) {
            load("$web/")
            val end = System.currentTimeMillis() + timeoutMs
            var last = "[]"
            var same = 0
            var lastCount = -1
            while (System.currentTimeMillis() < end) {
                Thread.sleep(1000)
                val r = js(JS_LIBRARY)
                val n = try { org.json.JSONArray(r).length() } catch (e: Exception) { 0 }
                if (n > 0) last = r
                if (n > 0 && n == lastCount) { if (++same >= 3) break } else same = 0
                lastCount = n
            }
            idleLater()
            return last
        }
    }

    /** The songs of a playlist page (or of Liked Songs), scrolled to the end:
     * {"name", "items": [{"title", "artist", "album", "duration", "cover"}], "total", "diag"}. */
    @JvmStatic
    fun tracks(url: String, timeoutMs: Long): String {
        if (appContext == null) return "{}"
        if (!url.startsWith(web)) return "{}"
        synchronized(lock) {
            load(url)
            Thread.sleep(2000)
            js(JS_SCRAPE)
            val end = System.currentTimeMillis() + timeoutMs
            var out = "{}"
            while (System.currentTimeMillis() < end) {
                Thread.sleep(1000)
                val r = js("JSON.stringify(window.__mfpScrape || null)")
                if (r.isEmpty() || r == "null") { js(JS_SCRAPE); continue }
                out = r
                try { if (JSONObject(r).optBoolean("done")) break } catch (e: Exception) { }
            }
            idleLater()
            return out
        }
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    /** The web player's desktop layout (library on the side) in the hidden page. */
    private const val JS_WIDE = """
(function(){ try {
  var m = document.querySelector('meta[name=viewport]');
  if (!m) { m = document.createElement('meta'); m.name = 'viewport'; (document.head || document.documentElement).appendChild(m); }
  m.content = 'width=1280';
} catch (e) {} })()"""

    private const val JS_LIBRARY = """
(function(){
  try { var m = document.querySelector('meta[name=viewport]'); if (m && m.content !== 'width=1280') m.content = 'width=1280'; } catch (e) {}
  var seen = {}, out = [];
  function add(id, name) {
    if (!id || seen[id]) return;
    seen[id] = 1;
    out.push({ id: id, name: ((name || '').trim().split('\n')[0] || 'Playlist').slice(0, 200) });
  }
  document.querySelectorAll('[aria-labelledby*="spotify:playlist:"], [id*="spotify:playlist:"]').forEach(function (el) {
    var m = ((el.getAttribute('aria-labelledby') || '') + ' ' + el.id).match(/spotify:playlist:([A-Za-z0-9]+)/);
    if (!m) return;
    var t = document.getElementById('listrow-title-spotify:playlist:' + m[1]);
    add(m[1], t ? t.textContent : (el.innerText || el.getAttribute('aria-label')));
  });
  var side = document.querySelector('#Desktop_LeftSidebar_Id, [data-testid="left-sidebar"], nav[aria-label]') || document;
  side.querySelectorAll('a[href*="/playlist/"]').forEach(function (a) {
    var m = (a.getAttribute('href') || '').match(/\/playlist\/([A-Za-z0-9]+)/);
    if (m) add(m[1], a.innerText || a.getAttribute('title') || a.getAttribute('aria-label'));
  });
  // The library list only shows what is on screen: move it down a screen for the next look.
  var row = side.querySelector('[role="row"], a[href*="/playlist/"]');
  for (var el = row, i = 0; el && i < 25; i++, el = el.parentElement) {
    var s = getComputedStyle(el).overflowY;
    if ((s === 'auto' || s === 'scroll') && el.scrollHeight > el.clientHeight) {
      el.scrollTop = Math.min(el.scrollTop + el.clientHeight, el.scrollHeight); break;
    }
  }
  return out;
})()"""

    /** Reads a song list page to the end (based on MusicFlow's playlist reader). */
    private const val JS_SCRAPE = """
(function(){
  if (window.__mfpScrape) return;
  try { var mv = document.querySelector('meta[name=viewport]'); if (mv) mv.content = 'width=1280'; } catch (e) {}
  var state = window.__mfpScrape = { done: false, items: [], total: 0, name: '', diag: '' };
  var store = {};
  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
  function rows() { return document.querySelectorAll('[data-testid="tracklist-row"]'); }
  function total() {
    var t = (document.querySelector('main') || document.body).innerText || '';
    var m = t.match(/([\d][\d\s  ,.]*)\s*(songs?|titres?|chansons?|canciones|músicas|songs)/i);
    return m ? parseInt(m[1].replace(/[^\d]/g, ''), 10) : 0;
  }
  function scroller() {
    var el = rows()[0];
    for (var i = 0; i < 25 && el; i++, el = el.parentElement) {
      var s = getComputedStyle(el).overflowY;
      if ((s === 'scroll' || s === 'auto') && el.scrollHeight > el.clientHeight) return el;
    }
    return document.scrollingElement;
  }
  function read() {
    rows().forEach(function (row, n) {
      var p = row.parentElement;
      var idx = (p && p.getAttribute('aria-rowindex')) || row.getAttribute('aria-rowindex');
      var link = row.querySelector('[data-testid="internal-track-link"]') || row.querySelector('a[href*="/track/"]');
      var title = link ? link.textContent.trim() : '';
      if (!title) return;
      if (!idx) idx = 'x' + title + '|' + n;
      var artists = [].slice.call(row.querySelectorAll('a[href*="/artist/"]')).map(function (a) { return a.textContent.trim(); })
        .filter(Boolean).filter(function (v, i, a) { return a.indexOf(v) === i; }).join(', ');
      var album = row.querySelector('a[href*="/album/"]');
      var img = row.querySelector('img');
      var d = (row.innerText || '').match(/\b\d{1,2}:\d{2}(:\d{2})?\b/g) || [];
      store[idx] = { title: title, artist: artists, album: album ? album.textContent.trim() : '',
                     duration: d.length ? d[d.length - 1] : '', cover: img ? (img.currentSrc || img.src || '') : '' };
    });
  }
  function count() { return Object.keys(store).length; }
  (async function () {
    try {
      for (var w = 0; w < 60 && !rows().length; w++) await sleep(500);
      var h1 = document.querySelector('main h1, [data-testid="entityTitle"] h1, h1');
      state.name = h1 ? h1.textContent.trim() : (document.title || '').replace(/\s*[|\-–]\s*Spotify.*$/i, '');
      if (!rows().length) {
        var txt = (document.body && document.body.innerText) || '';
        state.diag = JSON.stringify({ url: location.href.slice(0, 120), title: document.title.slice(0, 80),
          login: /\b(log in|se connecter|sign up|s'inscrire)\b/i.test(txt), text: txt.slice(0, 160).replace(/\s+/g, ' ') });
        state.done = true; return;
      }
      state.total = total();
      read();
      var sc = scroller();
      var stale = 0, last = -1, limit = Date.now() + 15 * 60 * 1000;
      while (Date.now() < limit) {
        sc.scrollTop = sc.scrollTop + Math.max(200, sc.clientHeight - 150);
        await sleep(400);
        read();
        state.items = Object.keys(store).sort(function (a, b) { return (+a) - (+b); }).map(function (k) { return store[k]; });
        if (state.total && count() >= state.total) break;
        var atEnd = sc.scrollTop + sc.clientHeight >= sc.scrollHeight - 4;
        if (count() === last) { if (++stale >= (atEnd ? 4 : 12)) break; } else stale = 0;
        last = count();
      }
      if (state.total && count() < state.total) {  // a second pass, slower, for the rows that were missed
        sc.scrollTop = 0; await sleep(800);
        while (Date.now() < limit && sc.scrollTop + sc.clientHeight < sc.scrollHeight - 4) {
          sc.scrollTop = sc.scrollTop + Math.max(150, sc.clientHeight / 2);
          await sleep(700); read();
        }
      }
      state.items = Object.keys(store).sort(function (a, b) { return (+a) - (+b); }).map(function (k) { return store[k]; });
    } catch (e) { state.diag = String(e); }
    state.done = true;
  })();
})()"""
}
