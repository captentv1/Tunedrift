package com.musicflow.play

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL

class PlaybackService : Service() {

    companion object {
        const val ACTION_UPDATE = "com.musicflow.play.UPDATE"
        const val ACTION_COMMAND = "com.musicflow.play.COMMAND"
        const val ACTION_STOP = "com.musicflow.play.STOP"
        const val EXTRA_COMMAND = "command"
        private const val CHANNEL_ID = "musicflow_play_playback"
        private const val NOTIF_ID = 2001

        @Volatile var commandSink: ((String, Long) -> Unit)? = null
        @Volatile var running = false
        @Volatile var title = ""
        @Volatile var artist = ""
        @Volatile var album = ""
        @Volatile var coverUrl = ""
        @Volatile var durationMs = 0L
        @Volatile var positionMs = 0L
        @Volatile var playing = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null
    private var cover: Bitmap? = null
    private var coverLoadedFor = ""

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) send("pause", 0)
        }
    }

    private fun send(command: String, value: Long) {
        mainHandler.post { commandSink?.invoke(command, value) }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        session = MediaSessionCompat(this, "MusicFlowPlay")
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = send("play", 0)
            override fun onPause() = send("pause", 0)
            override fun onStop() = send("pause", 0)
            override fun onSkipToNext() = send("next", 0)
            override fun onSkipToPrevious() = send("prev", 0)
            override fun onSeekTo(pos: Long) = send("seek", pos)
        })
        session.isActive = true
        try {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicFlowPlay:playback")
                .apply { setReferenceCounted(false) }
        } catch (e: Exception) { }
        ContextCompat.registerReceiver(
            this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refresh()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_COMMAND -> send(intent.getStringExtra(EXTRA_COMMAND) ?: "", 0)
        }
        refresh()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopForegroundCompat()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        try { unregisterReceiver(noisyReceiver) } catch (e: Exception) { }
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) { }
        try { session.isActive = false; session.release() } catch (e: Exception) { }
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) { }
    }

    private fun refresh() {
        loadCoverIfNeeded()
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, if (durationMs > 0) durationMs else -1L)
        cover?.let { metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(metadata.build())
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    positionMs, if (playing) 1f else 0f
                )
                .build()
        )
        try {
            if (playing) wakeLock?.acquire(4 * 60 * 60 * 1000L)
            else wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) { }
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification) } catch (e2: Exception) { }
        }
    }

    private fun loadCoverIfNeeded() {
        val wanted = coverUrl
        if (wanted == coverLoadedFor) return
        coverLoadedFor = wanted
        cover = null
        // Security: covers are only read from the app's own local server.
        if (!wanted.startsWith(MainActivity.PAGE_URL)) return
        Thread {
            var bitmap: Bitmap? = null
            try {
                val connection = URL(wanted).openConnection() as HttpURLConnection
                connection.connectTimeout = 4000
                connection.readTimeout = 8000
                val bytes = connection.inputStream.use { it.readBytes() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (e: Exception) { }
            mainHandler.post {
                if (running && coverLoadedFor == wanted && bitmap != null) {
                    cover = bitmap
                    refresh()
                }
            }
        }.start()
    }

    private fun commandIntent(command: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(ACTION_COMMAND).putExtra(EXTRA_COMMAND, command)
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(title.ifEmpty { "MusicFlow Play" })
            .setContentText(artist)
            .setContentIntent(openApp)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_previous, "Previous", commandIntent("prev", 1))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play", commandIntent(if (playing) "pause" else "play", 2)
            )
            .addAction(android.R.drawable.ic_media_next, "Next", commandIntent("next", 3))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        if (album.isNotEmpty()) builder.setSubText(album)
        cover?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
