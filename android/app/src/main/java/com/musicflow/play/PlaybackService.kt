package com.musicflow.play

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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

    private lateinit var player: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibraryService.MediaLibrarySession

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
            val connectionResult = super.onConnect(session, controller)
            val sessionCommands = connectionResult.availableSessionCommands.buildUpon()
                .add(SessionCommand("com.musicflow.play.SHUFFLE", Bundle.EMPTY))
                .add(SessionCommand("com.musicflow.play.REPEAT", Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(
                sessionCommands,
                connectionResult.availablePlayerCommands
            )
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
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle("Tunedrift")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
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
            "play" -> player.play()
            "pause" -> player.pause()
            "toggle" -> if (player.isPlaying) player.pause() else player.play()
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
        }
        syncStateFromPlayer()
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
        PlayerWidget.update(this, null as Bitmap?)
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
}
