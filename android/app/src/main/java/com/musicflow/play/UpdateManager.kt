package com.musicflow.play

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.PackageInfoCompat
import java.io.File

object UpdateManager {
    private const val PREFS = "mfp_update"
    private const val KEY_ID = "id"
    private const val UPDATE_CHANNEL = "musicflowplay_update"
    private const val NOTIF_ID = 4343
    const val EXTRA_INSTALLER = "installer_maj"

    fun file(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(ctx.filesDir, "Download"), "MusicFlowPlay-update.apk")

    private fun dm(ctx: Context) = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** Percent of the download under way (-1 = size unknown), or null when nothing is downloading. */
    @Volatile var progress: Int? = null
        private set

    /** Old versions downloaded with Android's download manager: forget that download (it could stay waiting). */
    fun forgetOldDownload(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong(KEY_ID, -1L)
        if (id >= 0) {
            try { dm(ctx).remove(id) } catch (e: Exception) { }
            prefs.edit().remove(KEY_ID).apply()
        }
    }

    /**
     * Downloads the update itself (no download manager: on some phones its download stayed "waiting" for ever, so
     * "Update" did nothing). Blocking; one at a time: a second call while one runs returns false at once.
     */
    fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): Boolean {
        synchronized(this) {
            if (progress != null) return false
            progress = -1
        }
        try {
            forgetOldDownload(ctx)
            val f = file(ctx)
            f.parentFile?.mkdirs()
            val part = File(f.path + ".part")
            part.delete(); f.delete()
            var address = url
            var conn: java.net.HttpURLConnection
            var hops = 0
            while (true) {  // GitHub sends the file from another address (redirect)
                conn = (java.net.URL(address).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 20000; readTimeout = 30000; instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "MusicFlowPlay")
                }
                val code = conn.responseCode
                if (code in 300..399 && hops < 8) {
                    val next = conn.getHeaderField("Location") ?: throw Exception("download failed (redirect)")
                    conn.disconnect()
                    address = java.net.URL(java.net.URL(address), next).toString()
                    if (!address.startsWith("https://")) throw Exception("download address refused")
                    hops++
                    continue
                }
                if (code != 200) { conn.disconnect(); throw Exception("download failed (error $code)") }
                break
            }
            val total = conn.contentLengthLong
            var done = 0L
            var last = -2
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt().coerceAtMost(99) else -1
                        if (pct != last) { last = pct; progress = pct; onProgress(pct) }
                    }
                }
            }
            conn.disconnect()
            if (total > 0 && done != total) throw Exception("download interrupted, try again")
            if (!part.renameTo(f)) throw Exception("could not save the update")
            return true
        } finally {
            progress = null
        }
    }

    // Security: only install Playwave itself, newer, signed with the same key.
    fun verify(ctx: Context, apk: File) {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val newInfo = pm.getPackageArchiveInfo(apk.path, flags)
        if (newInfo == null || newInfo.packageName != ctx.packageName) {
            apk.delete(); throw Exception("this file is not Playwave")
        }
        val current = pm.getPackageInfo(ctx.packageName, flags)
        if (PackageInfoCompat.getLongVersionCode(newInfo) <= PackageInfoCompat.getLongVersionCode(current)) {
            apk.delete(); throw Exception("this version is not newer")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signers = newInfo.signingInfo?.apkContentsSigners
            val mine = current.signingInfo?.apkContentsSigners
            if (signers != null && mine != null && signers.none { s -> mine.any { it == s } }) {
                apk.delete(); throw Exception("signature differs from the installed app")
            }
        }
    }

    fun readyVersion(ctx: Context): String {
        val f = file(ctx)
        if (!f.exists() || progress != null) return ""
        return try { verify(ctx, f); ctx.packageManager.getPackageArchiveInfo(f.path, 0)?.versionName ?: "" }
        catch (e: Exception) { "" }
    }

    fun notifyReady(ctx: Context, version: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(UPDATE_CHANNEL, "Playwave updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val openIntent = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_INSTALLER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTIF_ID, NotificationCompat.Builder(ctx, UPDATE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Playwave $version is ready")
            .setContentText("Tap to install the update")
            .setContentIntent(openIntent).setAutoCancel(true).build())
    }

    fun clearNotification(ctx: Context) =
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
}
