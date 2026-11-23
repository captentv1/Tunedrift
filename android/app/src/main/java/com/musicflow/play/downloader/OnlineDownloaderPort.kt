package com.musicflow.play.downloader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

data class OnlineTrackResult(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val coverUrl: String,
    val streamUrl: String = ""
)

data class DownloadProgress(
    val isDownloading: Boolean = false,
    val currentTitle: String = "",
    val percent: Int = 0,
    val lastMessage: String = ""
)

interface OnlineDownloaderPort {
    val isSupported: Boolean
    val progress: StateFlow<DownloadProgress>

    suspend fun searchOnline(query: String): List<OnlineTrackResult>
    suspend fun downloadTrack(
        context: Context,
        item: OnlineTrackResult,
        targetFolderUri: Uri?,
        onCompleted: suspend () -> Unit
    ): Result<String>
}
