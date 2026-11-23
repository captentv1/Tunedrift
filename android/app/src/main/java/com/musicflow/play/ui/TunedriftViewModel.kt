package com.musicflow.play.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.musicflow.play.data.MusicRepository
import com.musicflow.play.data.PlaylistEntity
import com.musicflow.play.data.SafFolderEntity
import com.musicflow.play.data.TextNormalizer
import com.musicflow.play.data.TrackEntity
import com.musicflow.play.downloader.OnlineTrackResult
import com.musicflow.play.downloader.PlatformDownloaderProvider
import com.musicflow.play.media.AudioMetadataHelper
import com.musicflow.play.media.AudioTagEdit
import com.musicflow.play.media.LrcLibCandidate
import com.musicflow.play.player.PlaybackManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppTab {
    HOME, LIBRARY, SEARCH, PLAYLISTS, SETTINGS
}

enum class LibrarySubTab {
    SONGS, ARTISTS, ALBUMS, FOLDERS, FAVORITES
}

enum class SortOption(val label: String) {
    TITLE("Titre"),
    ARTIST("Artiste"),
    ALBUM("Album"),
    DATE_ADDED("Ajout récent"),
    MOST_PLAYED("Plus écoutés"),
    RATING("Note")
}

class TunedriftViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("tunedrift_prefs", Context.MODE_PRIVATE)
    val repository = MusicRepository(application)
    val playbackManager = PlaybackManager(application, repository)

    private val _selectedTab = MutableStateFlow(AppTab.HOME)
    val selectedTab: StateFlow<AppTab> = _selectedTab.asStateFlow()

    private val _librarySubTab = MutableStateFlow(LibrarySubTab.SONGS)
    val librarySubTab: StateFlow<LibrarySubTab> = _librarySubTab.asStateFlow()

    private val _sortOption = MutableStateFlow(SortOption.TITLE)
    val sortOption: StateFlow<SortOption> = _sortOption.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _themeMode = MutableStateFlow(prefs.getString("theme_mode", "dark") ?: "dark")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColors = MutableStateFlow(prefs.getBoolean("dynamic_colors", false))
    val dynamicColors: StateFlow<Boolean> = _dynamicColors.asStateFlow()

    private val _showFullPlayer = MutableStateFlow(false)
    val showFullPlayer: StateFlow<Boolean> = _showFullPlayer.asStateFlow()

    private val _showQueueSheet = MutableStateFlow(false)
    val showQueueSheet: StateFlow<Boolean> = _showQueueSheet.asStateFlow()

    private val _editingTrack = MutableStateFlow<TrackEntity?>(null)
    val editingTrack: StateFlow<TrackEntity?> = _editingTrack.asStateFlow()

    private val _selectedPlaylistId = MutableStateFlow<Long?>(null)
    val selectedPlaylistId: StateFlow<Long?> = _selectedPlaylistId.asStateFlow()

    private val _onlineResults = MutableStateFlow<List<OnlineTrackResult>>(emptyList())
    val onlineResults: StateFlow<List<OnlineTrackResult>> = _onlineResults.asStateFlow()

    private val _isSearchingOnline = MutableStateFlow(false)
    val isSearchingOnline: StateFlow<Boolean> = _isSearchingOnline.asStateFlow()

    private val _lrcCandidates = MutableStateFlow<List<LrcLibCandidate>>(emptyList())
    val lrcCandidates: StateFlow<List<LrcLibCandidate>> = _lrcCandidates.asStateFlow()

    private val _isSearchingLyrics = MutableStateFlow(false)
    val isSearchingLyrics: StateFlow<Boolean> = _isSearchingLyrics.asStateFlow()

    private val _currentArtwork = MutableStateFlow<Bitmap?>(null)
    val currentArtwork: StateFlow<Bitmap?> = _currentArtwork.asStateFlow()

    val downloadProgress = PlatformDownloaderProvider.progress
    val hasDownloader: Boolean = PlatformDownloaderProvider.isSupported

    val allTracks: StateFlow<List<TrackEntity>> = combine(
        repository.allTracks,
        _sortOption
    ) { list, sort ->
        when (sort) {
            SortOption.TITLE -> list.sortedBy { it.titleNorm }
            SortOption.ARTIST -> list.sortedBy { it.artistNorm }
            SortOption.ALBUM -> list.sortedBy { it.albumNorm }
            SortOption.DATE_ADDED -> list.sortedByDescending { it.dateAddedSec }
            SortOption.MOST_PLAYED -> list.sortedByDescending { it.playCount }
            SortOption.RATING -> list.sortedByDescending { it.rating }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteTracks: StateFlow<List<TrackEntity>> = repository.favoriteTracks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<PlaylistEntity>> = repository.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val safFolders: StateFlow<List<SafFolderEntity>> = repository.safFolders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredSearchTracks: StateFlow<List<TrackEntity>> = combine(
        repository.allTracks,
        _searchQuery
    ) { tracks, q ->
        if (q.isBlank()) tracks
        else tracks.filter { TextNormalizer.matches(q, it.title, it.artist, it.album, it.folderName) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activePlaylistTracks: StateFlow<List<TrackEntity>> = _selectedPlaylistId
        .flatMapLatest { id ->
            if (id == null) kotlinx.coroutines.flow.flowOf(emptyList())
            else repository.observePlaylistTracks(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        playbackManager.connect()
        viewModelScope.launch {
            allTracks.collect { tracks ->
                playbackManager.updateTrackCatalog(tracks)
            }
        }
        viewModelScope.launch {
            playbackManager.state.collect { playerState ->
                val track = playerState.currentTrack
                if (track != null) {
                    _currentArtwork.value = AudioMetadataHelper.loadArtworkBitmap(
                        getApplication(),
                        track,
                        maxDimension = 512
                    )
                } else {
                    _currentArtwork.value = null
                }
            }
        }
    }

    fun selectTab(tab: AppTab) {
        _selectedTab.value = tab
    }

    fun selectLibrarySubTab(sub: LibrarySubTab) {
        _librarySubTab.value = sub
    }

    fun setSortOption(option: SortOption) {
        _sortOption.value = option
    }

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
    }

    fun setShowFullPlayer(show: Boolean) {
        _showFullPlayer.value = show
    }

    fun setShowQueueSheet(show: Boolean) {
        _showQueueSheet.value = show
    }

    fun openTagEditor(track: TrackEntity?) {
        _editingTrack.value = track
    }

    fun openPlaylist(playlistId: Long?) {
        _selectedPlaylistId.value = playlistId
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun setThemeMode(mode: String) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode).apply()
    }

    fun setDynamicColors(enabled: Boolean) {
        _dynamicColors.value = enabled
        prefs.edit().putBoolean("dynamic_colors", enabled).apply()
    }

    fun scanLibrary() {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true
            val count = runCatching { repository.rescanLibrary() }.getOrDefault(0)
            _isScanning.value = false
            _statusMessage.value = "$count titres synchronisés"
        }
    }

    fun addSafFolder(treeUri: Uri) {
        viewModelScope.launch {
            val folder = repository.registerSafFolder(treeUri)
            _statusMessage.value = "Dossier ajouté : ${folder.displayName}"
            scanLibrary()
        }
    }

    fun removeSafFolder(treeUri: String) {
        viewModelScope.launch {
            repository.removeSafFolder(treeUri)
            scanLibrary()
        }
    }

    fun toggleFavorite(track: TrackEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(track)
        }
    }

    fun setRating(track: TrackEntity, rating: Int) {
        viewModelScope.launch {
            repository.setRating(track.id, rating)
        }
    }

    fun saveTagEdit(track: TrackEntity, edit: AudioTagEdit) {
        viewModelScope.launch {
            repository.editTrackTags(track, edit)
            _editingTrack.value = null
            _statusMessage.value = "Tags enregistrés"
        }
    }

    fun searchLyricsOnline(track: TrackEntity) {
        viewModelScope.launch {
            _isSearchingLyrics.value = true
            _lrcCandidates.value = repository.fetchOnlineLyrics(track)
            _isSearchingLyrics.value = false
        }
    }

    fun applyLyricsCandidate(track: TrackEntity, candidate: LrcLibCandidate) {
        viewModelScope.launch {
            repository.applyLyricsCandidate(track, candidate)
            _lrcCandidates.value = emptyList()
            _statusMessage.value = "Paroles appliquées"
        }
    }

    fun autoSyncLyrics(track: TrackEntity) {
        viewModelScope.launch {
            _isSearchingLyrics.value = true
            val ok = repository.syncLyricsFromLrcLib(track)
            _isSearchingLyrics.value = false
            _statusMessage.value = if (ok) "Paroles synchronisées trouvées" else "Aucune parole synchronisée trouvée"
        }
    }

    fun createPlaylist(name: String, description: String = "") {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createPlaylist(name, description)
            _statusMessage.value = "Playlist « $name » créée"
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
            if (_selectedPlaylistId.value == playlistId) _selectedPlaylistId.value = null
        }
    }

    fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        viewModelScope.launch {
            repository.addTrackToPlaylist(playlistId, trackId)
            _statusMessage.value = "Titre ajouté à la playlist"
        }
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) {
        viewModelScope.launch {
            repository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }

    fun searchOnlineMusic(query: String) {
        if (!hasDownloader || query.isBlank()) return
        viewModelScope.launch {
            _isSearchingOnline.value = true
            _onlineResults.value = PlatformDownloaderProvider.searchOnline(query)
            _isSearchingOnline.value = false
        }
    }

    fun downloadOnlineTrack(item: OnlineTrackResult) {
        if (!hasDownloader) return
        viewModelScope.launch(Dispatchers.IO) {
            val targetUri = safFolders.value.firstOrNull()?.treeUri?.let { Uri.parse(it) }
            val result = PlatformDownloaderProvider.downloadTrack(
                context = getApplication(),
                item = item,
                targetFolderUri = targetUri
            ) {
                repository.rescanLibrary()
            }
            _statusMessage.value = result.fold(
                onSuccess = { "Téléchargé : ${item.title}" },
                onFailure = { "Erreur : ${it.message}" }
            )
        }
    }

    override fun onCleared() {
        playbackManager.disconnect()
        super.onCleared()
    }
}
