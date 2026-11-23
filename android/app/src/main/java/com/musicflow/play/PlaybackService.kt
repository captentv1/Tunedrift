package com.musicflow.play

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.musicflow.play.data.TextNormalizer
import com.musicflow.play.data.TrackEntity
import com.musicflow.play.data.TunedriftDatabase
import com.musicflow.play.media.AudioMetadataHelper
import com.musicflow.play.player.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class PlaybackService : MediaLibraryService() {

    companion object {
        const val ACTION_UPDATE = "com.musicflow.play.UPDATE"
        const val ACTION_COMMAND = "com.musicflow.play.COMMAND"
        const val ACTION_STOP = "com.musicflow.play.STOP"
        const val ACTION_CLOSE = "com.musicflow.play.CLOSE"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_FOREGROUND = "foreground"

        @Volatile var running = false
        @Volatile var title = ""
        @Volatile var artist = ""
        @Volatile var album = ""
        @Volatile var durationMs = 0L
        @Volatile var positionMs = 0L
        @Volatile var playing = false
        @Volatile var shuffle = false
        @Volatile var repeat = "off"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var player: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibraryService.MediaLibrarySession
    private var lastLoadedArtTrackId = -1L
    private var cachedArtBitmap: Bitmap? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                player.pause()
                syncStateFromPlayer()
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncStateFromPlayer()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncStateFromPlayer()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncStateFromPlayer()
            loadAndAttachArtwork(mediaItem)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            shuffle = shuffleModeEnabled
            PlayerWidget.draw(this@PlaybackService)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            repeat = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> "one"
                Player.REPEAT_MODE_ALL -> "all"
                else -> "off"
            }
            PlayerWidget.draw(this@PlaybackService)
        }
    }

    private val libraryCallback = object : MediaLibraryService.MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            val customCommands = base.availableSessionCommands.buildUpon()
                .add(SessionCommand("com.musicflow.play.SHUFFLE", Bundle.EMPTY))
                .add(SessionCommand("com.musicflow.play.REPEAT", Bundle.EMPTY))
                .add(SessionCommand("com.musicflow.play.AUDIO_PARAMS", Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(customCommands, base.availablePlayerCommands)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                "com.musicflow.play.SHUFFLE" -> {
                    player.shuffleModeEnabled = !player.shuffleModeEnabled
                    syncStateFromPlayer()
                }
                "com.musicflow.play.REPEAT" -> {
                    player.repeatMode = when (player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    syncStateFromPlayer()
                }
                "com.musicflow.play.AUDIO_PARAMS" -> {
                    val speed = args.getFloat("speed", 1.0f).coerceIn(0.5f, 2.0f)
                    val pitch = args.getFloat("pitch", 1.0f).coerceIn(0.5f, 2.0f)
                    player.playbackParameters = PlaybackParameters(speed, pitch)
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Tunedrift")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val db = TunedriftDatabase.getInstance(this@PlaybackService)
            val resultFuture = com.google.common.util.concurrent.SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                val items = when {
                    parentId == "root" -> listOf(
                        browsableItem("shuffle_all", "Lecture aléatoire", "Toute la bibliothèque", playable = true),
                        browsableItem("favorites", "Favoris", "Titres aimés", playable = false),
                        browsableItem("playlists", "Playlists", "Vos playlists", playable = false),
                        browsableItem("all_tracks", "Tous les titres", "Bibliothèque complète", playable = false)
                    )
                    parentId == "favorites" -> {
                        db.trackDao().getAllOnce().filter { it.isFavorite }.map { autoTrackItem("fav:${it.id}", it) }
                    }
                    parentId == "all_tracks" -> {
                        db.trackDao().getAllOnce().take(250).map { autoTrackItem("track:${it.id}", it) }
                    }
                    parentId == "playlists" -> {
                        val list = mutableListOf<MediaItem>()
                        val tracks = db.trackDao().getAllOnce()
                        if (tracks.isNotEmpty()) {
                            list.add(browsableItem("recent_added", "Ajoutés récemment", "${minOf(50, tracks.size)} titres", playable = false))
                        }
                        list
                    }
                    parentId == "recent_added" -> {
                        db.trackDao().getAllOnce().sortedByDescending { it.dateAddedSec }.take(50)
                            .map { autoTrackItem("track:${it.id}", it) }
                    }
                    else -> emptyList()
                }
                resultFuture.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            }
            return resultFuture
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val db = TunedriftDatabase.getInstance(this@PlaybackService)
            val future = com.google.common.util.concurrent.SettableFuture.create<MutableList<MediaItem>>()
            serviceScope.launch {
                val allTracks = db.trackDao().getAllOnce()
                val byId = allTracks.associateBy { it.id }
                val resolved = mutableListOf<MediaItem>()
                for (item in mediaItems) {
                    val id = item.mediaId
                    when {
                        id == "shuffle_all" -> {
                            player.shuffleModeEnabled = true
                            resolved.addAll(allTracks.shuffled().map { PlaybackManager.toMediaItem(it) })
                        }
                        id.startsWith("fav:") -> {
                            val favs = allTracks.filter { it.isFavorite }
                            val targetId = id.removePrefix("fav:").toLongOrNull()
                            val ordered = if (targetId != null) {
                                val idx = favs.indexOfFirst { it.id == targetId }.coerceAtLeast(0)
                                favs.drop(idx) + favs.take(idx)
                            } else {
                                favs
                            }
                            resolved.addAll(ordered.map { PlaybackManager.toMediaItem(it) })
                        }
                        id.startsWith("track:") -> {
                            val targetId = id.removePrefix("track:").toLongOrNull()
                            val found = targetId?.let { byId[it] }
                            if (found != null) {
                                resolved.add(PlaybackManager.toMediaItem(found))
                            }
                        }
                        item.localConfiguration?.uri != null -> {
                            resolved.add(item)
                        }
                        else -> {
                            val numericId = id.toLongOrNull()
                            val found = numericId?.let { byId[it] }
                            if (found != null) resolved.add(PlaybackManager.toMediaItem(found))
                        }
                    }
                }
                future.set(resolved)
            }
            return future
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            session.notifySearchResultChanged(browser, query, 20, params)
            return Futures.immediateFuture(LibraryResult.ofVoid(params))
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val db = TunedriftDatabase.getInstance(this@PlaybackService)
            val future = com.google.common.util.concurrent.SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                val matches = db.trackDao().getAllOnce()
                    .filter { TextNormalizer.matches(query, it.title, it.artist, it.album) }
                    .take(30)
                    .map { autoTrackItem("track:${it.id}", it) }
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(matches), params))
            }
            return future
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(playerListener)

        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaLibrarySession = MediaLibraryService.MediaLibrarySession.Builder(
            this,
            player,
            libraryCallback
        )
            .setSessionActivity(sessionActivity)
            .build()

        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        syncStateFromPlayer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP, ACTION_CLOSE -> {
                player.pause()
                syncStateFromPlayer()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_COMMAND -> {
                handleCommand(intent.getStringExtra(EXTRA_COMMAND).orEmpty())
            }
        }
        syncStateFromPlayer()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun handleCommand(command: String) {
        when (command) {
            "play" -> {
                if (player.mediaItemCount == 0) {
                    loadAllAndPlay(shuffle = false, favoritesOnly = false)
                } else {
                    player.play()
                }
            }
            "pause" -> player.pause()
            "toggle" -> if (player.isPlaying) player.pause() else handleCommand("play")
            "next" -> if (player.hasNextMediaItem()) player.seekToNextMediaItem()
            "prev" -> if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
            "shuffle" -> player.shuffleModeEnabled = !player.shuffleModeEnabled
            "repeat" -> {
                player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
            "shuffleall" -> loadAllAndPlay(shuffle = true, favoritesOnly = false)
            "favorites" -> loadAllAndPlay(shuffle = false, favoritesOnly = true)
            "mixtop", "mixnew" -> loadAllAndPlay(shuffle = false, favoritesOnly = false)
            "fav" -> toggleCurrentFavorite()
        }
        syncStateFromPlayer()
    }

    private fun loadAllAndPlay(shuffle: Boolean, favoritesOnly: Boolean) {
        val db = TunedriftDatabase.getInstance(this)
        serviceScope.launch {
            val tracks = db.trackDao().getAllOnce().let { list ->
                if (favoritesOnly) list.filter { it.isFavorite } else list
            }
            if (tracks.isEmpty()) return@launch
            val items = tracks.map { PlaybackManager.toMediaItem(it) }
            ContextCompat.getMainExecutor(this@PlaybackService).execute {
                player.shuffleModeEnabled = shuffle
                player.setMediaItems(items, 0, 0L)
                player.prepare()
                player.play()
                syncStateFromPlayer()
            }
        }
    }

    private fun toggleCurrentFavorite() {
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val db = TunedriftDatabase.getInstance(this)
        serviceScope.launch {
            val track = db.trackDao().getById(id) ?: return@launch
            val nextFav = !track.isFavorite
            db.trackDao().setFavorite(id, nextFav)
            PlayerWidget.favorite = nextFav
            ContextCompat.getMainExecutor(this@PlaybackService).execute {
                PlayerWidget.draw(this@PlaybackService)
            }
        }
    }

    private fun loadAndAttachArtwork(mediaItem: MediaItem?) {
        val trackId = mediaItem?.mediaId?.toLongOrNull() ?: return
        if (trackId == lastLoadedArtTrackId && cachedArtBitmap != null) {
            PlayerWidget.update(this, cachedArtBitmap)
            return
        }
        lastLoadedArtTrackId = trackId
        val db = TunedriftDatabase.getInstance(this)
        serviceScope.launch {
            val track = db.trackDao().getById(trackId) ?: return@launch
            val bmp = AudioMetadataHelper.loadArtworkBitmap(this@PlaybackService, track, maxDimension = 512)
            cachedArtBitmap = bmp
            PlayerWidget.favorite = track.isFavorite
            if (bmp != null) {
                val stream = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val artBytes = stream.toByteArray()
                ContextCompat.getMainExecutor(this@PlaybackService).execute {
                    val idx = player.currentMediaItemIndex
                    if (idx in 0 until player.mediaItemCount) {
                        val current = player.getMediaItemAt(idx)
                        if (current.mediaId == trackId.toString()) {
                            val updatedMeta = current.mediaMetadata.buildUpon()
                                .setArtworkData(artBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                .build()
                            player.replaceMediaItem(
                                idx,
                                current.buildUpon().setMediaMetadata(updatedMeta).build()
                            )
                        }
                    }
                    PlayerWidget.update(this@PlaybackService, bmp)
                }
            } else {
                ContextCompat.getMainExecutor(this@PlaybackService).execute {
                    PlayerWidget.update(this@PlaybackService, null)
                }
            }
        }
    }

    private fun syncStateFromPlayer() {
        val current = player.currentMediaItem
        val meta = current?.mediaMetadata
        title = meta?.title?.toString().orEmpty()
        artist = meta?.artist?.toString().orEmpty()
        album = meta?.albumTitle?.toString().orEmpty()
        playing = player.isPlaying
        durationMs = player.duration.coerceAtLeast(0L)
        positionMs = player.currentPosition.coerceAtLeast(0L)
        shuffle = player.shuffleModeEnabled
        repeat = when (player.repeatMode) {
            Player.REPEAT_MODE_ONE -> "one"
            Player.REPEAT_MODE_ALL -> "all"
            else -> "off"
        }

        val upcoming = mutableListOf<String>()
        val idx = player.currentMediaItemIndex
        if (idx >= 0) {
            for (nextIdx in (idx + 1)..minOf(idx + 3, player.mediaItemCount - 1)) {
                val m = player.getMediaItemAt(nextIdx).mediaMetadata
                val t = m.title?.toString().orEmpty()
                val a = m.artist?.toString().orEmpty()
                if (t.isNotEmpty()) {
                    upcoming.add(if (a.isNotEmpty()) "$t · $a" else t)
                }
            }
        }
        PlayerWidget.upNext = upcoming
        PlayerWidget.update(this, cachedArtBitmap)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.isPlaying) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        player.removeListener(playerListener)
        mediaLibrarySession.release()
        player.release()
        runCatching { unregisterReceiver(noisyReceiver) }
        super.onDestroy()
    }

    private fun browsableItem(id: String, title: String, subtitle: String, playable: Boolean): MediaItem {
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(!playable)
                    .setIsPlayable(playable)
                    .build()
            )
            .build()
    }

    private fun autoTrackItem(autoId: String, track: TrackEntity): MediaItem {
        return MediaItem.Builder()
            .setMediaId(autoId)
            .setUri(Uri.parse(track.uri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }
}
