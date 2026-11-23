package com.musicflow.play.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.musicflow.play.PlaybackService
import com.musicflow.play.data.MusicRepository
import com.musicflow.play.data.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlaybackUiState(
    val currentTrack: TrackEntity? = null,
    val queue: List<TrackEntity> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val playbackSpeed: Float = 1.0f,
    val playbackPitch: Float = 1.0f,
    val sleepTimerRemainingMs: Long = 0L
)

class PlaybackManager(
    private val context: Context,
    private val repository: MusicRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var trackMapById = emptyMap<Long, TrackEntity>()
    private var sleepDeadlineMs = 0L
    private var lastRecordedTrackId = -1L

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshFromPlayer()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refreshFromPlayer()
            val current = _state.value.currentTrack
            if (current != null && current.id != lastRecordedTrackId) {
                lastRecordedTrackId = current.id
                scope.launch(Dispatchers.IO) {
                    repository.recordPlay(current.id)
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            refreshFromPlayer()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            refreshFromPlayer()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            refreshFromPlayer()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            refreshFromPlayer()
        }
    }

    init {
        startPositionTicker()
    }

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (!future.isCancelled) {
                    val ctrl = runCatching { future.get() }.getOrNull()
                    controller = ctrl
                    ctrl?.addListener(listener)
                    refreshFromPlayer()
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    fun disconnect() {
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    fun updateTrackCatalog(tracks: List<TrackEntity>) {
        trackMapById = tracks.associateBy { it.id }
        refreshFromPlayer()
    }

    fun playTracks(tracks: List<TrackEntity>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
        trackMapById = trackMapById + tracks.associateBy { it.id }

        val mediaItems = tracks.map { toMediaItem(it) }
        val ctrl = controller
        if (ctrl != null) {
            ctrl.shuffleModeEnabled = shuffle
            ctrl.setMediaItems(mediaItems, safeIndex, 0L)
            ctrl.prepare()
            ctrl.play()
            refreshFromPlayer()
        } else {
            val intent = Intent(context, PlaybackService::class.java)
            ContextCompat.startForegroundService(context, intent)
            connect()
            scope.launch {
                repeat(20) {
                    delay(100)
                    controller?.let { c ->
                        c.shuffleModeEnabled = shuffle
                        c.setMediaItems(mediaItems, safeIndex, 0L)
                        c.prepare()
                        c.play()
                        refreshFromPlayer()
                        return@launch
                    }
                }
            }
        }

        scope.launch(Dispatchers.IO) {
            repository.saveQueue(tracks.map { it.id })
        }
    }

    fun playNext(track: TrackEntity) {
        val ctrl = controller ?: return
        trackMapById = trackMapById + (track.id to track)
        val insertIndex = if (ctrl.mediaItemCount == 0) 0 else ctrl.currentMediaItemIndex + 1
        ctrl.addMediaItem(insertIndex, toMediaItem(track))
        if (ctrl.mediaItemCount == 1) {
            ctrl.prepare()
            ctrl.play()
        }
        persistCurrentQueue()
    }

    fun addToQueue(track: TrackEntity) {
        val ctrl = controller ?: return
        trackMapById = trackMapById + (track.id to track)
        ctrl.addMediaItem(toMediaItem(track))
        if (ctrl.mediaItemCount == 1) {
            ctrl.prepare()
            ctrl.play()
        }
        persistCurrentQueue()
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val ctrl = controller ?: return
        if (fromIndex !in 0 until ctrl.mediaItemCount || toIndex !in 0 until ctrl.mediaItemCount) return
        ctrl.moveMediaItem(fromIndex, toIndex)
        persistCurrentQueue()
    }

    fun removeQueueItem(index: Int) {
        val ctrl = controller ?: return
        if (index !in 0 until ctrl.mediaItemCount) return
        ctrl.removeMediaItem(index)
        persistCurrentQueue()
    }

    fun skipToQueueIndex(index: Int) {
        val ctrl = controller ?: return
        if (index !in 0 until ctrl.mediaItemCount) return
        ctrl.seekToDefaultPosition(index)
        ctrl.play()
        refreshFromPlayer()
    }

    fun togglePlayPause() {
        val ctrl = controller ?: return
        if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
        refreshFromPlayer()
    }

    fun next() {
        val ctrl = controller ?: return
        if (ctrl.hasNextMediaItem()) {
            ctrl.seekToNextMediaItem()
            ctrl.play()
        }
        refreshFromPlayer()
    }

    fun previous() {
        val ctrl = controller ?: return
        if (ctrl.currentPosition > 3000L) {
            ctrl.seekTo(0L)
        } else if (ctrl.hasPreviousMediaItem()) {
            ctrl.seekToPreviousMediaItem()
        } else {
            ctrl.seekTo(0L)
        }
        refreshFromPlayer()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
        refreshFromPlayer()
    }

    fun toggleShuffle() {
        val ctrl = controller ?: return
        ctrl.shuffleModeEnabled = !ctrl.shuffleModeEnabled
        refreshFromPlayer()
    }

    fun cycleRepeatMode() {
        val ctrl = controller ?: return
        ctrl.repeatMode = when (ctrl.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        refreshFromPlayer()
    }

    fun setSpeedAndPitch(speed: Float, pitch: Float) {
        val ctrl = controller ?: return
        val s = speed.coerceIn(0.5f, 2.0f)
        val p = pitch.coerceIn(0.5f, 2.0f)
        ctrl.setPlaybackSpeed(s)
        val args = Bundle().apply {
            putFloat("speed", s)
            putFloat("pitch", p)
        }
        ctrl.sendCustomCommand(SessionCommand("com.musicflow.play.AUDIO_PARAMS", Bundle.EMPTY), args)
        _state.update { it.copy(playbackSpeed = s, playbackPitch = p) }
    }

    fun setSleepTimerMinutes(minutes: Int) {
        sleepDeadlineMs = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes * 60_000L
        _state.update {
            it.copy(
                sleepTimerRemainingMs = if (sleepDeadlineMs > 0L) sleepDeadlineMs - System.currentTimeMillis() else 0L
            )
        }
    }

    private fun persistCurrentQueue() {
        refreshFromPlayer()
        val ids = _state.value.queue.map { it.id }
        scope.launch(Dispatchers.IO) {
            repository.saveQueue(ids)
        }
    }

    private fun refreshFromPlayer() {
        val ctrl = controller ?: return
        val items = mutableListOf<TrackEntity>()
        for (i in 0 until ctrl.mediaItemCount) {
            val item = ctrl.getMediaItemAt(i)
            val id = item.mediaId.toLongOrNull()
            val found = if (id != null) trackMapById[id] else null
            if (found != null) {
                items.add(found)
            } else {
                val meta = item.mediaMetadata
                items.add(
                    TrackEntity(
                        id = id ?: -(i + 1L),
                        uri = item.localConfiguration?.uri?.toString().orEmpty(),
                        title = meta.title?.toString().orEmpty(),
                        artist = meta.artist?.toString().orEmpty(),
                        album = meta.albumTitle?.toString().orEmpty()
                    )
                )
            }
        }
        val idx = ctrl.currentMediaItemIndex
        val current = items.getOrNull(idx)
        _state.update {
            it.copy(
                currentTrack = current,
                queue = items,
                currentIndex = idx,
                isPlaying = ctrl.isPlaying,
                positionMs = ctrl.currentPosition.coerceAtLeast(0L),
                durationMs = ctrl.duration.coerceAtLeast(current?.durationMs ?: 0L),
                shuffleEnabled = ctrl.shuffleModeEnabled,
                repeatMode = ctrl.repeatMode
            )
        }
    }

    private fun startPositionTicker() {
        scope.launch {
            while (isActive) {
                val ctrl = controller
                if (ctrl != null && ctrl.isPlaying) {
                    val remaining = if (sleepDeadlineMs > 0L) {
                        (sleepDeadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
                    } else {
                        0L
                    }
                    if (sleepDeadlineMs > 0L && remaining == 0L) {
                        sleepDeadlineMs = 0L
                        ctrl.pause()
                    }
                    _state.update {
                        it.copy(
                            positionMs = ctrl.currentPosition.coerceAtLeast(0L),
                            durationMs = ctrl.duration.coerceAtLeast(it.currentTrack?.durationMs ?: 0L),
                            isPlaying = ctrl.isPlaying,
                            sleepTimerRemainingMs = remaining
                        )
                    }
                }
                delay(400L)
            }
        }
    }

    companion object {
        fun toMediaItem(track: TrackEntity): MediaItem {
            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .setAlbumArtist(track.albumArtist.ifEmpty { track.artist })
                .setGenre(track.genre)
                .setTrackNumber(track.trackNumber.takeIf { it > 0 })
                .setReleaseYear(track.year.takeIf { it > 0 })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()

            return MediaItem.Builder()
                .setMediaId(track.id.toString())
                .setUri(Uri.parse(track.uri))
                .setMediaMetadata(metadata)
                .build()
        }
    }
}
