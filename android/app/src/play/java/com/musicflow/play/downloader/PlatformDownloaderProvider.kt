package com.musicflow.play.downloader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Variante Google Play : aucun module de téléchargement externe inclus.
 */
object PlatformDownloaderProvider : OnlineDownloaderPort {
    override val isSupported: Boolean = false

    private val _progress = MutableStateFlow(DownloadProgress())
    override val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    override suspend fun searchOnline(query: String): List<OnlineTrackResult> = emptyList()

    override suspend fun downloadTrack(
        context: Context,
        item: OnlineTrackResult,
        targetFolderUri: Uri?,
        onCompleted: suspend () -> Unit
    ): Result<String> = Result.failure(UnsupportedOperationException("Indisponible sur la version Google Play"))
}
