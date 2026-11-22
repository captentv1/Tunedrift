package com.musicflow.play

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import java.net.HttpURLConnection
import java.net.URL

/** The player's notification, lock screen and headset controls; also what Android Auto browses and plays
 *  (the music itself plays in the app's page). */
class PlaybackService : MediaBrowserServiceCompat() {

    companion object {
        const val ACTION_UPDATE = "com.musicflow.play.UPDATE"
        const val ACTION_COMMAND = "com.musicflow.play.COMMAND"
        const val ACTION_STOP = "com.musicflow.play.STOP"
        /** The notification's ✕, or the notification swiped away while paused (as in Samsung Music). */
        const val ACTION_CLOSE = "com.musicflow.play.CLOSE"
        const val EXTRA_COMMAND = "command"
        /** Set when the service is started with startForegroundService: Android then wants startForeground soon. */
        const val EXTRA_FOREGROUND = "foreground"
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
        @Volatile var shuffle = false
        @Volatile var repeat = "off"
        private const val CUSTOM_SHUFFLE = "shuffle"
        private const val CUSTOM_REPEAT = "repeat"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null
    private var cover: Bitmap? = null
    private var coverLoadedFor = ""
    // What was last sent to the system: the page reports several events per song (play, playing, duration, seek…),
    // and resending the metadata with its cover, the notification and the widget each time kept the phone busy.
    private var sentMeta = ""
    private var sentNotif = ""
    private var sentWidget = ""
    private var widgetCover: Bitmap? = null
    private var inForeground = false
    private var mustStartForeground = false

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
            override fun onSetShuffleMode(mode: Int) = send("shuffle", 0)
            override fun onSetRepeatMode(mode: Int) = send("repeat", 0)
            override fun onPlayFromMediaId(mediaId: String?, extras: android.os.Bundle?) {
                val id = mediaId ?: return
                if (id.matches(Regex("[a-z]+:[A-Za-z0-9_]*(:\\d+)?"))) MainActivity.callPage("window.MFP_auto && MFP_auto('$id')")
            }
            override fun onPlayFromSearch(query: String?, extras: android.os.Bundle?) {
                MainActivity.callPage("window.MFP_autoSearch && MFP_autoSearch(${org.json.JSONObject.quote(query ?: "")})")
            }
            override fun onCustomAction(action: String?, extras: android.os.Bundle?) {
                if (action == CUSTOM_SHUFFLE) send("shuffle", 0) else if (action == CUSTOM_REPEAT) send("repeat", 0)
            }
        })
        session.isActive = true
        sessionToken = session.sessionToken
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
        // Every startForegroundService() must be followed by startForeground(), even when the service already runs
        // (otherwise Android stops the whole app a few seconds later: one of the crashes while downloading).
        if (intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true || intent?.action == ACTION_COMMAND) {
            mustStartForeground = true
            sentNotif = ""
        }
        when (intent?.action) {
            ACTION_STOP -> {
                if (mustStartForeground) refresh()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CLOSE -> {
                if (mustStartForeground) refresh()
                playing = false
                send("pause", 0)
                stopForegroundCompat()
                try { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) } catch (e: Exception) { }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_COMMAND -> send(intent.getStringExtra(EXTRA_COMMAND) ?: "", 0)
        }
        refresh()
        return START_NOT_STICKY
    }

    // ---- Android Auto: what can be browsed in the car ----
    private val carApps = setOf("com.google.android.projection.gearhead", "com.google.android.wearable.app",
        "com.google.android.autosimulator", "com.google.android.carassistant", "com.google.android.googlequicksearchbox")

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: android.os.Bundle?): BrowserRoot? =
        if (clientPackageName == packageName || clientPackageName in carApps || clientUid == android.os.Process.SYSTEM_UID) BrowserRoot("root", null) else null

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.detach()
        Thread {
            val items = try { children(parentId) } catch (e: Exception) {
                mutableListOf(item("none", "Open Playwave on the phone first", "", false))
            }
            try { result.sendResult(items) } catch (e: Exception) { }
        }.start()
    }

    private fun item(id: String, title: String, sub: String, playable: Boolean) = MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(sub).build(),
        if (playable) MediaBrowserCompat.MediaItem.FLAG_PLAYABLE else MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)

    private fun local(path: String): org.json.JSONObject {
        val c = URL(MainActivity.PAGE_URL + "api/" + path).openConnection() as HttpURLConnection
        c.setRequestProperty("X-MFP-Token", MainActivity.API_TOKEN)
        c.connectTimeout = 3000
        c.readTimeout = 15000
        return org.json.JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    private fun children(parent: String): MutableList<MediaBrowserCompat.MediaItem> {
        val out = mutableListOf<MediaBrowserCompat.MediaItem>()
        when {
            parent == "root" -> {
                out += item("shuffle:", "Shuffle everything", "All your music", true)
                out += item("fav", "Favorites", "", false)
                out += item("pls", "Playlists", "", false)
                out += item("mixes", "Mixes made for you", "", false)
            }
            parent == "mixes" -> listOf("top" to "Most played", "week" to "On repeat", "new" to "Recently added",
                "rediscover" to "Rediscover", "never" to "Never played", "rated" to "Top rated", "favmix" to "Favorites mix")
                .forEach { (k, n) -> out += item("mix:$k:0", n, "Mix", true) }
            parent == "pls" -> {
                val pls = local("collections").optJSONArray("playlists") ?: org.json.JSONArray()
                for (k in 0 until pls.length()) {
                    val p = pls.getJSONObject(k)
                    val smart = p.has("rules")
                    out += item(if (smart) "pl:${p.getString("id")}:0" else "pl/${p.getString("id")}", p.optString("name"),
                        if (smart) "Smart playlist" else "${p.optJSONArray("tracks")?.length() ?: 0} songs", smart)
                }
            }
            parent == "fav" || parent.startsWith("pl/") -> {
                val col = local("collections")
                val ids = if (parent == "fav") col.optJSONArray("favorites") else {
                    val pls = col.optJSONArray("playlists") ?: org.json.JSONArray()
                    (0 until pls.length()).map { pls.getJSONObject(it) }.firstOrNull { it.optString("id") == parent.removePrefix("pl/") }?.optJSONArray("tracks")
                } ?: org.json.JSONArray()
                val tracks = local("tracks").optJSONArray("tracks") ?: org.json.JSONArray()
                val byId = HashMap<String, org.json.JSONObject>()
                for (k in 0 until tracks.length()) tracks.getJSONObject(k).let { byId[it.optString("id")] = it }
                var n = 0
                for (k in 0 until minOf(ids.length(), 300)) {
                    val t = byId[ids.optString(k)] ?: continue
                    val kind = if (parent == "fav") "fav:" else "pl:${parent.removePrefix("pl/")}"
                    out += item("$kind:$n", t.optString("title"), t.optString("artist"), true)
                    n++
                }
            }
        }
        return out
    }

    // Swiped away from the recent apps: the music goes on (the page lives on without its window, see WebHost),
    // as in Samsung Music. Paused, the app closes with its notification.
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!playing) {
            stopForegroundCompat()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        PlayerWidget.update(this, null)
        try { unregisterReceiver(noisyReceiver) } catch (e: Exception) { }
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) { }
        try { session.isActive = false; session.release() } catch (e: Exception) { }
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        inForeground = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) { }
    }

    private fun refresh() {
        loadCoverIfNeeded()
        val coverId = System.identityHashCode(cover)
        val widgetKey = "$title|$artist|$playing|$coverId|$shuffle|$repeat"
        if (widgetKey != sentWidget) {
            sentWidget = widgetKey
            PlayerWidget.update(this, widgetCover)
        }
        val metaKey = "$title|$artist|$album|$durationMs|$coverId"
        if (metaKey != sentMeta) {
            sentMeta = metaKey
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, if (durationMs > 0) durationMs else -1L)
            cover?.let { metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
            session.setMetadata(metadata.build())
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                        PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
                )
                .addCustomAction(CUSTOM_SHUFFLE, if (shuffle) "Shuffle: on" else "Shuffle: off", shuffleIcon())
                .addCustomAction(CUSTOM_REPEAT, repeatLabel(), repeatIcon())
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    positionMs, if (playing) 1f else 0f
                )
                .build()
        )
        session.setShuffleMode(if (shuffle) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
        session.setRepeatMode(when (repeat) {
            "one" -> PlaybackStateCompat.REPEAT_MODE_ONE
            "all" -> PlaybackStateCompat.REPEAT_MODE_ALL
            else -> PlaybackStateCompat.REPEAT_MODE_NONE
        })
        try {
            if (playing) wakeLock?.acquire(4 * 60 * 60 * 1000L)
            else wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) { }
        val notifKey = "$title|$artist|$album|$playing|$shuffle|$repeat|$coverId"
        if (notifKey == sentNotif && !mustStartForeground) return
        val notification = buildNotification()
        val nm = getSystemService(NotificationManager::class.java)
        if (playing || mustStartForeground) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NOTIF_ID, notification)
                }
                inForeground = true
            } catch (e: Exception) {
                try { nm.notify(NOTIF_ID, notification) } catch (e2: Exception) { }
            }
            mustStartForeground = false
        }
        if (!playing) {
            // Paused: the notification stays, but can be swiped away (which closes the player), as in Samsung Music.
            if (inForeground) {
                inForeground = false
                try { stopForeground(STOP_FOREGROUND_DETACH) } catch (e: Exception) { }
            }
            try { nm.notify(NOTIF_ID, notification) } catch (e: Exception) { }
        }
        sentNotif = notifKey
    }

    private fun loadCoverIfNeeded() {
        val wanted = coverUrl
        if (wanted == coverLoadedFor) return
        coverLoadedFor = wanted
        cover = null
        widgetCover = null
        // Security: covers are only read from the app's own local server.
        if (!wanted.startsWith(MainActivity.PAGE_URL)) return
        Thread {
            var bitmap: Bitmap? = null
            var small: Bitmap? = null
            try {
                val connection = URL(wanted).openConnection() as HttpURLConnection
                connection.connectTimeout = 4000
                connection.readTimeout = 8000
                val bytes = connection.inputStream.use { it.readBytes() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                // 512 px is plenty for the lock screen and the notification; 1024 px covers (4 MB each) were
                // copied to the system at every update.
                while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                bitmap = scaled(decoded, 512)
                small = scaled(bitmap, 256)
            } catch (e: Exception) { }
            mainHandler.post {
                if (running && coverLoadedFor == wanted && bitmap != null) {
                    cover = bitmap
                    widgetCover = small
                    refresh()
                }
            }
        }.start()
    }

    private fun scaled(b: Bitmap?, max: Int): Bitmap? {
        if (b == null || (b.width <= max && b.height <= max)) return b
        val k = max.toFloat() / maxOf(b.width, b.height)
        return Bitmap.createScaledBitmap(b, maxOf(1, (b.width * k).toInt()), maxOf(1, (b.height * k).toInt()), true)
    }

    private fun shuffleIcon() = if (shuffle) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle_off
    private fun repeatIcon() = when (repeat) {
        "one" -> R.drawable.ic_repeat_one
        "all" -> R.drawable.ic_repeat_all
        else -> R.drawable.ic_repeat_off
    }
    private fun repeatLabel() = when (repeat) {
        "one" -> "Repeat: this song"
        "all" -> "Repeat: all"
        else -> "Repeat: off"
    }

    private fun commandIntent(command: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(ACTION_COMMAND).putExtra(EXTRA_COMMAND, command)
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun closeIntent(): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(ACTION_CLOSE)
        return PendingIntent.getService(this, 6, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(title.ifEmpty { "Playwave" })
            .setContentText(artist)
            .setContentIntent(openApp)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(shuffleIcon(), if (shuffle) "Shuffle: on" else "Shuffle: off", commandIntent("shuffle", 4))
            .addAction(android.R.drawable.ic_media_previous, "Previous", commandIntent("prev", 1))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play", commandIntent(if (playing) "pause" else "play", 2)
            )
            .addAction(android.R.drawable.ic_media_next, "Next", commandIntent("next", 3))
            // Up to Android 12 a media notification shows 5 buttons: repeat stays on the lock screen and in the app.
            .addAction(R.drawable.ic_close, "Close", closeIntent())
            .setDeleteIntent(closeIntent())
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(1, 2, 3)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(closeIntent())
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
