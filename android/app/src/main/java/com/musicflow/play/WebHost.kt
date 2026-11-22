package com.musicflow.play

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.view.ViewGroup
import android.webkit.WebView
import java.lang.ref.WeakReference

/**
 * The player's page lives here, not in the window: the music plays in the page, so when the app is swiped away
 * from the recent apps (its window closes) the page and the music go on, kept alive by the playback service,
 * as in Samsung Music. When the app opens again the same page is put back in the new window.
 */
object WebHost {
    @SuppressLint("StaticFieldLeak")
    var webView: WebView? = null
        private set
    private var wrapper: MutableContextWrapper? = null
    private var activityRef: WeakReference<MainActivity>? = null

    val activity: MainActivity? get() = activityRef?.get()?.takeIf { !it.isDestroyed }

    /** Returns the page and true if it is new (to configure and load), false if it was kept from before. */
    fun obtain(activity: MainActivity): Pair<WebView, Boolean> {
        activityRef = WeakReference(activity)
        webView?.let { w ->
            wrapper?.setBaseContext(activity)  // dialogs and pickers of the page need the window's context
            (w.parent as? ViewGroup)?.removeView(w)
            return w to false
        }
        val ctx = MutableContextWrapper(activity)
        val w = WebView(ctx)
        wrapper = ctx
        webView = w
        return w to true
    }

    /** A page with no window yet (started by a widget or the notification while the app is closed). */
    fun obtainHeadless(app: Context): WebView {
        webView?.let { return it }
        ready = false
        val ctx = MutableContextWrapper(app)
        val w = WebView(ctx)
        wrapper = ctx
        webView = w
        return w
    }

    /** True once the page has loaded the library and can play: commands sent before wait in [pending]. */
    @Volatile var ready = false
    /** What a widget or the notification asked for while the page was starting: "cmd:play", "widget:shuffleall"… */
    @Volatile var pending = ""

    /** The window closes: the page stays (music playing) or is freed. */
    fun detach(activity: MainActivity, keep: Boolean) {
        if (activityRef?.get() === activity) activityRef = null
        val w = webView ?: return
        (w.parent as? ViewGroup)?.removeView(w)
        wrapper?.setBaseContext(activity.applicationContext)
        if (!keep) release()
    }

    /** Frees the page (also when Android ended its renderer to get memory back). */
    fun release() {
        val w = webView ?: return
        webView = null
        ready = false
        wrapper = null
        (w.parent as? ViewGroup)?.removeView(w)
        try { w.destroy() } catch (e: Exception) { }
    }

    fun eval(js: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { webView?.evaluateJavascript(js, null) } catch (e: Exception) { }
        }
    }

    fun appContext(): Context? = webView?.context?.applicationContext
}
