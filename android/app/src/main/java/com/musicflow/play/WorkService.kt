package com.musicflow.play

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps downloads, imports, lyrics synchronization and the albums fix going with the screen off or the app closed.
 *
 * It replaces MusicFlow's download service, which was started again with startForegroundService() at every progress
 * update without calling startForeground() again: while music played (the app then counts as in the foreground),
 * Android stopped the whole app a few seconds later ("did not then call startForeground"). Here the service is
 * started once, always calls startForeground() when started, and progress only updates its notification.
 */
class WorkService : Service() {

    companion object {
        private const val CHANNEL_ID = "musicflow_play_work"
        private const val NOTIF_ID = 2002
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_PERCENT = "percent"
        @Volatile var running = false

        fun start(ctx: Context, text: String, percent: Int) {
            val intent = Intent(ctx, WorkService::class.java).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_PERCENT, percent)
            try {
                if (running) ctx.startService(intent) else ContextCompat.startForegroundService(ctx, intent)
            } catch (e: Exception) {
                // Android 12+: not allowed from the background. The work goes on while the app stays alive.
            }
        }

        fun update(ctx: Context, text: String, percent: Int) {
            if (!running) return start(ctx, text, percent)
            try { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(ctx, text, percent)) } catch (e: Exception) { }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, WorkService::class.java)) } catch (e: Exception) { }
        }

        private fun build(ctx: Context, text: String, percent: Int): Notification {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID, "Downloads and imports", NotificationManager.IMPORTANCE_LOW)
                channel.setShowBadge(false)
                ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
            val open = PendingIntent.getActivity(
                ctx, 20, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "download")
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Tunedrift")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
            if (percent in 0..100) b.setProgress(100, percent, false) else b.setProgress(0, 0, true)
            return b.build()
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        try {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicFlowPlay:work").apply {
                    setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
                }
        } catch (e: Exception) { }
        try {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MusicFlowPlay:wifi").apply {
                    setReferenceCounted(false); acquire()
                }
        } catch (e: Exception) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build(this, intent?.getStringExtra(EXTRA_TEXT) ?: "Working…", intent?.getIntExtra(EXTRA_PERCENT, -1) ?: -1)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) { }
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) { }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { }
        super.onDestroy()
    }
}
