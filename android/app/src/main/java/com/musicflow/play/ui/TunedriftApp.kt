package com.musicflow.play.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.musicflow.play.BuildConfig
import com.musicflow.play.data.PlaylistEntity
import com.musicflow.play.data.SafFolderEntity
import com.musicflow.play.data.TrackEntity
import com.musicflow.play.downloader.OnlineTrackResult
import com.musicflow.play.media.AudioTagEdit
import com.musicflow.play.media.LrcLibCandidate
import com.musicflow.play.media.LyricsManager
import com.musicflow.play.player.PlaybackUiState
import com.musicflow.play.ui.theme.TunedriftTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunedriftApp(
    viewModel: TunedriftViewModel,
    hasAudioPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onPickSafFolder: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val dynamicColors by viewModel.dynamicColors.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val allTracks by viewModel.allTracks.collectAsState()
    val favoriteTracks by viewModel.favoriteTracks.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val safFolders by viewModel.safFolders.collectAsState()
    val playbackState by viewModel.playbackManager.state.collectAsState()
    val currentArtwork by viewModel.currentArtwork.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val showFullPlayer by viewModel.showFullPlayer.collectAsState()
    val showQueueSheet by viewModel.showQueueSheet.collectAsState()
    val editingTrack by viewModel.editingTrack.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var playlistPickerTrack by remember { mutableStateOf<TrackEntity?>(null) }

    LaunchedEffect(statusMessage) {
        val msg = statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearStatusMessage()
    }

    TunedriftTheme(themeMode = themeMode, dynamicColor = dynamicColors) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                Column(modifier = Modifier.navigationBarsPadding()) {
                    if (downloadProgress.isDownloading) {
                        LinearProgressIndicator(
                            progress = { (downloadProgress.percent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (playbackState.currentTrack != null) {
                        MiniPlayerBar(
                            state = playbackState,
                            artwork = currentArtwork,
                            onOpenPlayer = { viewModel.setShowFullPlayer(true) },
                            onTogglePlayPause = { viewModel.playbackManager.togglePlayPause() },
                            onNext = { viewModel.playbackManager.next() },
                            onToggleFavorite = {
                                playbackState.currentTrack?.let { viewModel.toggleFavorite(it) }
                            }
                        )
                    }
                    NavigationBar {
                        NavigationBarItem(
                            selected = selectedTab == AppTab.HOME,
                            onClick = { viewModel.selectTab(AppTab.HOME) },
                            icon = { Icon(Icons.Default.Home, contentDescription = "Accueil") },
                            label = { Text("Accueil") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == AppTab.LIBRARY,
                            onClick = { viewModel.selectTab(AppTab.LIBRARY) },
                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Bibliothèque") },
                            label = { Text("Musique") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == AppTab.SEARCH,
                            onClick = { viewModel.selectTab(AppTab.SEARCH) },
                            icon = { Icon(Icons.Default.Search, contentDescription = "Recherche") },
                            label = { Text("Recherche") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == AppTab.PLAYLISTS,
                            onClick = { viewModel.selectTab(AppTab.PLAYLISTS) },
                            icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Playlists") },
                            label = { Text("Playlists") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == AppTab.SETTINGS,
                            onClick = { viewModel.selectTab(AppTab.SETTINGS) },
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Réglages") },
                            label = { Text("Réglages") }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (selectedTab) {
                    AppTab.HOME -> HomeScreen(
                        allTracks = allTracks,
                        favoriteTracks = favoriteTracks,
                        playlists = playlists,
                        hasAudioPermission = hasAudioPermission,
                        isScanning = isScanning,
                        onRequestPermissions = onRequestPermissions,
                        onPickSafFolder = onPickSafFolder,
                        onRescan = { viewModel.scanLibrary() },
                        onPlayTracks = { list, idx, shuffle ->
                            viewModel.playbackManager.playTracks(list, idx, shuffle)
                        },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        onEditTags = { viewModel.openTagEditor(it) },
                        onAddToPlaylist = { playlistPickerTrack = it },
                        onPlayNext = { viewModel.playbackManager.playNext(it) },
                        onAddToQueue = { viewModel.playbackManager.addToQueue(it) },
                        onOpenPlaylist = {
                            viewModel.openPlaylist(it.id)
                            viewModel.selectTab(AppTab.PLAYLISTS)
                        }
                    )

                    AppTab.LIBRARY -> LibraryScreen(
                        viewModel = viewModel,
                        allTracks = allTracks,
                        favoriteTracks = favoriteTracks,
                        onPlayTracks = { list, idx, shuffle ->
                            viewModel.playbackManager.playTracks(list, idx, shuffle)
                        },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        onEditTags = { viewModel.openTagEditor(it) },
                        onAddToPlaylist = { playlistPickerTrack = it },
                        onPlayNext = { viewModel.playbackManager.playNext(it) },
                        onAddToQueue = { viewModel.playbackManager.addToQueue(it) }
                    )

                    AppTab.SEARCH -> SearchScreen(
                        viewModel = viewModel,
                        onPlayTracks = { list, idx ->
                            viewModel.playbackManager.playTracks(list, idx, false)
                        },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        onEditTags = { viewModel.openTagEditor(it) },
                        onAddToPlaylist = { playlistPickerTrack = it },
                        onPlayNext = { viewModel.playbackManager.playNext(it) },
                        onAddToQueue = { viewModel.playbackManager.addToQueue(it) }
                    )

                    AppTab.PLAYLISTS -> PlaylistsScreen(
                        viewModel = viewModel,
                        playlists = playlists,
                        favoriteTracks = favoriteTracks,
                        onPlayTracks = { list, idx, shuffle ->
                            viewModel.playbackManager.playTracks(list, idx, shuffle)
                        },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        onEditTags = { viewModel.openTagEditor(it) },
                        onAddToPlaylist = { playlistPickerTrack = it },
                        onPlayNext = { viewModel.playbackManager.playNext(it) },
                        onAddToQueue = { viewModel.playbackManager.addToQueue(it) }
                    )

                    AppTab.SETTINGS -> SettingsScreen(
                        themeMode = themeMode,
                        dynamicColors = dynamicColors,
                        safFolders = safFolders,
                        trackCount = allTracks.size,
                        isScanning = isScanning,
                        hasDownloader = viewModel.hasDownloader,
                        onThemeChange = { viewModel.setThemeMode(it) },
                        onDynamicColorChange = { viewModel.setDynamicColors(it) },
                        onPickSafFolder = onPickSafFolder,
                        onRemoveSafFolder = { viewModel.removeSafFolder(it) },
                        onRescan = { viewModel.scanLibrary() },
                        onOpenUrl = onOpenUrl
                    )
                }
            }
        }

        if (showFullPlayer && playbackState.currentTrack != null) {
            FullPlayerSheet(
                viewModel = viewModel,
                state = playbackState,
                artwork = currentArtwork,
                onDismiss = { viewModel.setShowFullPlayer(false) },
                onOpenQueue = { viewModel.setShowQueueSheet(true) },
                onEditTags = { viewModel.openTagEditor(it) }
            )
        }

        if (showQueueSheet) {
            QueueBottomSheet(
                state = playbackState,
                onDismiss = { viewModel.setShowQueueSheet(false) },
                onSelectIndex = { viewModel.playbackManager.skipToQueueIndex(it) },
                onMoveItem = { from, to -> viewModel.playbackManager.moveQueueItem(from, to) },
                onRemoveItem = { viewModel.playbackManager.removeQueueItem(it) }
            )
        }

        editingTrack?.let { target ->
            TagEditorDialog(
                track = target,
                onDismiss = { viewModel.openTagEditor(null) },
                onSave = { edit -> viewModel.saveTagEdit(target, edit) }
            )
        }

        playlistPickerTrack?.let { target ->
            AddToPlaylistDialog(
                track = target,
                playlists = playlists,
                onDismiss = { playlistPickerTrack = null },
                onCreateAndAdd = { name ->
                    viewModel.createPlaylist(name)
                    playlistPickerTrack = null
                },
                onSelectPlaylist = { plId ->
                    viewModel.addTrackToPlaylist(plId, target.id)
                    playlistPickerTrack = null
                }
            )
        }
    }
}

@Composable
private fun MiniPlayerBar(
    state: PlaybackUiState,
    artwork: Bitmap?,
    onOpenPlayer: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val track = state.currentTrack ?: return
    Surface(
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenPlayer() }
    ) {
        Column {
            val progress = if (state.durationMs > 0L) {
                (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtworkBox(artwork = artwork, sizeDp = 44)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favori",
                        tint = if (track.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Lecture/Pause"
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Suivant")
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    allTracks: List<TrackEntity>,
    favoriteTracks: List<TrackEntity>,
    playlists: List<PlaylistEntity>,
    hasAudioPermission: Boolean,
    isScanning: Boolean,
    onRequestPermissions: () -> Unit,
    onPickSafFolder: () -> Unit,
    onRescan: () -> Unit,
    onPlayTracks: (List<TrackEntity>, Int, Boolean) -> Unit,
    onToggleFavorite: (TrackEntity) -> Unit,
    onEditTags: (TrackEntity) -> Unit,
    onAddToPlaylist: (TrackEntity) -> Unit,
    onPlayNext: (TrackEntity) -> Unit,
    onAddToQueue: (TrackEntity) -> Unit,
    onOpenPlaylist: (PlaylistEntity) -> Unit
) {
    val recentTracks = remember(allTracks) {
        allTracks.sortedByDescending { it.dateAddedSec }.take(15)
    }
    val topTracks = remember(allTracks) {
        allTracks.filter { it.playCount > 0 }.sortedByDescending { it.playCount }.take(10)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Tunedrift",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${allTracks.size} titres · ${favoriteTracks.size} favoris",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRescan, enabled = !isScanning) {
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualiser")
                    }
                }
            }
        }

        if (!hasAudioPermission) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Autorisation audio requise",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Autorisez l'accès aux fichiers audio (READ_MEDIA_AUDIO) ou sélectionnez un dossier via SAF.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onRequestPermissions) {
                                Text("Autoriser")
                            }
                            OutlinedButton(onClick = onPickSafFolder) {
                                Text("Dossier SAF")
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onPlayTracks(allTracks, 0, true) },
                    enabled = allTracks.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Shuffle, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Tout mélanger")
                }
                OutlinedButton(
                    onClick = { onPlayTracks(favoriteTracks, 0, false) },
                    enabled = favoriteTracks.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Favorite, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Favoris (${favoriteTracks.size})")
                }
            }
        }

        if (playlists.isNotEmpty()) {
            item {
                Text("Playlists", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(playlists, key = { it.id }) { pl ->
                        Card(
                            modifier = Modifier
                                .width(160.dp)
                                .clickable { onOpenPlaylist(pl) }
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(pl.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (pl.description.isNotBlank()) {
                                    Text(
                                        pl.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (topTracks.isNotEmpty()) {
            item {
                Text("Les plus écoutés", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            itemsIndexed(topTracks, key = { _, t -> "top_${t.id}" }) { index, track ->
                TrackRow(
                    track = track,
                    onClick = { onPlayTracks(topTracks, index, false) },
                    onToggleFavorite = { onToggleFavorite(track) },
                    onEditTags = { onEditTags(track) },
                    onAddToPlaylist = { onAddToPlaylist(track) },
                    onPlayNext = { onPlayNext(track) },
                    onAddToQueue = { onAddToQueue(track) }
                )
            }
        }

        if (recentTracks.isNotEmpty()) {
            item {
                Text("Ajoutés récemment", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            itemsIndexed(recentTracks, key = { _, t -> "rec_${t.id}" }) { index, track ->
                TrackRow(
                    track = track,
                    onClick = { onPlayTracks(recentTracks, index, false) },
                    onToggleFavorite = { onToggleFavorite(track) },
                    onEditTags = { onEditTags(track) },
                    onAddToPlaylist = { onAddToPlaylist(track) },
                    onPlayNext = { onPlayNext(track) },
                    onAddToQueue = { onAddToQueue(track) }
                )
            }
        } else if (allTracks.isEmpty()) {
            item {
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Aucune musique trouvée", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Ajoutez vos dossiers contenant des fichiers MP3, FLAC, M4A, OPUS, OGG ou WAV.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onPickSafFolder) {
                            Text("Choisir un dossier audio")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    viewModel: TunedriftViewModel,
    allTracks: List<TrackEntity>,
    favoriteTracks: List<TrackEntity>,
    onPlayTracks: (List<TrackEntity>, Int, Boolean) -> Unit,
    onToggleFavorite: (TrackEntity) -> Unit,
    onEditTags: (TrackEntity) -> Unit,
    onAddToPlaylist: (TrackEntity) -> Unit,
    onPlayNext: (TrackEntity) -> Unit,
    onAddToQueue: (TrackEntity) -> Unit
) {
    val subTab by viewModel.librarySubTab.collectAsState()
    val sortOption by viewModel.sortOption.collectAsState()
    var showSortMenu by remember { mutableStateOf(false) }

    val artists = remember(allTracks) {
        allTracks.groupBy { it.artist }.toList().sortedBy { it.first.lowercase() }
    }
    val albums = remember(allTracks) {
        allTracks.groupBy { it.album }.toList().sortedBy { it.first.lowercase() }
    }
    val folders = remember(allTracks) {
        allTracks.groupBy { it.folderName.ifEmpty { "Musique" } }.toList().sortedBy { it.first.lowercase() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Bibliothèque", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Box {
                TextButton(onClick = { showSortMenu = true }) {
                    Text("Trier : ${sortOption.label}")
                }
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    SortOption.entries.forEach { opt ->
                        DropdownMenuItem(
                            text = { Text(opt.label) },
                            onClick = {
                                viewModel.setSortOption(opt)
                                showSortMenu = false
                            }
                        )
                    }
                }
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = subTab == LibrarySubTab.SONGS,
                    onClick = { viewModel.selectLibrarySubTab(LibrarySubTab.SONGS) },
                    label = { Text("Titres (${allTracks.size})") }
                )
            }
            item {
                FilterChip(
                    selected = subTab == LibrarySubTab.ARTISTS,
                    onClick = { viewModel.selectLibrarySubTab(LibrarySubTab.ARTISTS) },
                    label = { Text("Artistes (${artists.size})") }
                )
            }
            item {
                FilterChip(
                    selected = subTab == LibrarySubTab.ALBUMS,
                    onClick = { viewModel.selectLibrarySubTab(LibrarySubTab.ALBUMS) },
                    label = { Text("Albums (${albums.size})") }
                )
            }
            item {
                FilterChip(
                    selected = subTab == LibrarySubTab.FOLDERS,
                    onClick = { viewModel.selectLibrarySubTab(LibrarySubTab.FOLDERS) },
                    label = { Text("Dossiers (${folders.size})") }
                )
            }
            item {
                FilterChip(
                    selected = subTab == LibrarySubTab.FAVORITES,
                    onClick = { viewModel.selectLibrarySubTab(LibrarySubTab.FAVORITES) },
                    label = { Text("Favoris (${favoriteTracks.size})") }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (subTab) {
            LibrarySubTab.SONGS -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(allTracks, key = { _, t -> t.id }) { index, track ->
                        TrackRow(
                            track = track,
                            onClick = { onPlayTracks(allTracks, index, false) },
                            onToggleFavorite = { onToggleFavorite(track) },
                            onEditTags = { onEditTags(track) },
                            onAddToPlaylist = { onAddToPlaylist(track) },
                            onPlayNext = { onPlayNext(track) },
                            onAddToQueue = { onAddToQueue(track) }
                        )
                    }
                }
            }

            LibrarySubTab.FAVORITES -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(favoriteTracks, key = { _, t -> "fav_${t.id}" }) { index, track ->
                        TrackRow(
                            track = track,
                            onClick = { onPlayTracks(favoriteTracks, index, false) },
                            onToggleFavorite = { onToggleFavorite(track) },
                            onEditTags = { onEditTags(track) },
                            onAddToPlaylist = { onAddToPlaylist(track) },
                            onPlayNext = { onPlayNext(track) },
                            onAddToQueue = { onAddToQueue(track) }
                        )
                    }
                }
            }

            LibrarySubTab.ARTISTS -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(artists, key = { it.first }) { (artistName, tracks) ->
                        GroupCard(
                            title = artistName,
                            subtitle = "${tracks.size} titres",
                            icon = Icons.Default.Person,
                            onPlay = { onPlayTracks(tracks, 0, false) }
                        )
                    }
                }
            }

            LibrarySubTab.ALBUMS -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(albums, key = { it.first }) { (albumName, tracks) ->
                        val artistHint = tracks.firstOrNull()?.artist.orEmpty()
                        GroupCard(
                            title = albumName,
                            subtitle = "$artistHint · ${tracks.size} titres",
                            icon = Icons.Default.Album,
                            onPlay = { onPlayTracks(tracks.sortedBy { it.trackNumber }, 0, false) }
                        )
                    }
                }
            }

            LibrarySubTab.FOLDERS -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(folders, key = { it.first }) { (folderName, tracks) ->
                        GroupCard(
                            title = folderName,
                            subtitle = "${tracks.size} fichiers audio",
                            icon = Icons.Default.Folder,
                            onPlay = { onPlayTracks(tracks, 0, false) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(
    viewModel: TunedriftViewModel,
    onPlayTracks: (List<TrackEntity>, Int) -> Unit,
    onToggleFavorite: (TrackEntity) -> Unit,
    onEditTags: (TrackEntity) -> Unit,
    onAddToPlaylist: (TrackEntity) -> Unit,
    onPlayNext: (TrackEntity) -> Unit,
    onAddToQueue: (TrackEntity) -> Unit
) {
    val query by viewModel.searchQuery.collectAsState()
    val localResults by viewModel.filteredSearchTracks.collectAsState()
    val onlineResults by viewModel.onlineResults.collectAsState()
    val isSearchingOnline by viewModel.isSearchingOnline.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Text("Recherche", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.setSearchQuery(it) },
            placeholder = { Text("Titre, artiste, album (sans accents)...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (viewModel.hasDownloader && query.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.searchOnlineMusic(query) },
                enabled = !isSearchingOnline,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isSearchingOnline) "Recherche en ligne..." else "Rechercher « $query » en ligne (MusicFlow)")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (localResults.isNotEmpty()) {
                item {
                    Text(
                        "Dans votre musique (${localResults.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                itemsIndexed(localResults, key = { _, t -> "srch_${t.id}" }) { idx, track ->
                    TrackRow(
                        track = track,
                        onClick = { onPlayTracks(localResults, idx) },
                        onToggleFavorite = { onToggleFavorite(track) },
                        onEditTags = { onEditTags(track) },
                        onAddToPlaylist = { onAddToPlaylist(track) },
                        onPlayNext = { onPlayNext(track) },
                        onAddToQueue = { onAddToQueue(track) }
                    )
                }
            }

            if (viewModel.hasDownloader && onlineResults.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Résultats en ligne (MusicFlow)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                items(onlineResults, key = { "dl_${it.id}" }) { item ->
                    OnlineResultRow(
                        item = item,
                        onDownload = { viewModel.downloadOnlineTrack(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun OnlineResultRow(
    item: OnlineTrackResult,
    onDownload: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${item.artist} · ${item.album}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDownload) {
                Icon(Icons.Default.CloudDownload, contentDescription = "Télécharger")
            }
        }
    }
}

@Composable
private fun PlaylistsScreen(
    viewModel: TunedriftViewModel,
    playlists: List<PlaylistEntity>,
    favoriteTracks: List<TrackEntity>,
    onPlayTracks: (List<TrackEntity>, Int, Boolean) -> Unit,
    onToggleFavorite: (TrackEntity) -> Unit,
    onEditTags: (TrackEntity) -> Unit,
    onAddToPlaylist: (TrackEntity) -> Unit,
    onPlayNext: (TrackEntity) -> Unit,
    onAddToQueue: (TrackEntity) -> Unit
) {
    val selectedId by viewModel.selectedPlaylistId.collectAsState()
    val playlistTracks by viewModel.activePlaylistTracks.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    val activePlaylist = playlists.firstOrNull { it.id == selectedId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        if (activePlaylist != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.openPlaylist(null) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
                Text(
                    activePlaylist.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { viewModel.deletePlaylist(activePlaylist.id) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Supprimer la playlist")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { onPlayTracks(playlistTracks, 0, false) },
                enabled = playlistTracks.isNotEmpty()
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Lire la playlist (${playlistTracks.size})")
            }
            Spacer(modifier = Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(playlistTracks, key = { _, t -> "pl_${activePlaylist.id}_${t.id}" }) { idx, track ->
                    TrackRow(
                        track = track,
                        onClick = { onPlayTracks(playlistTracks, idx, false) },
                        onToggleFavorite = { onToggleFavorite(track) },
                        onEditTags = { onEditTags(track) },
                        onAddToPlaylist = { onAddToPlaylist(track) },
                        onPlayNext = { onPlayNext(track) },
                        onAddToQueue = { onAddToQueue(track) }
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Playlists & Favoris", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Button(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Nouvelle")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlayTracks(favoriteTracks, 0, false) }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Favorite, contentDescription = null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Titres favoris", fontWeight = FontWeight.Bold)
                        Text("${favoriteTracks.size} titres", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(playlists, key = { it.id }) { pl ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.openPlaylist(pl.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pl.name, fontWeight = FontWeight.SemiBold)
                                if (pl.description.isNotBlank()) {
                                    Text(pl.description, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Créer une playlist") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nom de la playlist") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text("Description (optionnel)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.createPlaylist(name, desc)
                        showCreateDialog = false
                    },
                    enabled = name.isNotBlank()
                ) {
                    Text("Créer")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }
}

@Composable
private fun SettingsScreen(
    themeMode: String,
    dynamicColors: Boolean,
    safFolders: List<SafFolderEntity>,
    trackCount: Int,
    isScanning: Boolean,
    hasDownloader: Boolean,
    onThemeChange: (String) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onPickSafFolder: () -> Unit,
    onRemoveSafFolder: (String) -> Unit,
    onRescan: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Réglages", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        item {
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Apparence", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("dark" to "Sombre", "oled" to "OLED", "light" to "Clair", "system" to "Auto").forEach { (id, label) ->
                            FilterChip(
                                selected = themeMode == id,
                                onClick = { onThemeChange(id) },
                                label = { Text(label) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Couleurs dynamiques (Material You)")
                        Switch(checked = dynamicColors, onCheckedChange = onDynamicColorChange)
                    }
                }
            }
        }

        item {
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Stockage & Dossiers SAF", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Tunedrift utilise MediaStore (READ_MEDIA_AUDIO) et le Storage Access Framework (SAF) sans accès complet au stockage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onPickSafFolder) {
                            Text("Ajouter un dossier SAF")
                        }
                        OutlinedButton(onClick = onRescan, enabled = !isScanning) {
                            Text("Scanner ($trackCount)")
                        }
                    }
                    safFolders.forEach { folder ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(folder.displayName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { onRemoveSafFolder(folder.treeUri) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Retirer")
                            }
                        }
                    }
                }
            }
        }

        item {
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("À propos de Tunedrift", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Version : ${BuildConfig.VERSION_NAME} (natif Kotlin + Compose + Media3)")
                    Text("Édition : ${BuildConfig.DISTRIBUTION.uppercase()} · Téléchargement MusicFlow : ${if (hasDownloader) "Activé" else "Désactivé (Play)"}")
                    TextButton(onClick = { onOpenUrl("https://github.com/captentv1/tunedrift") }) {
                        Text("Dépôt GitHub captentv1/tunedrift")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullPlayerSheet(
    viewModel: TunedriftViewModel,
    state: PlaybackUiState,
    artwork: Bitmap?,
    onDismiss: () -> Unit,
    onOpenQueue: () -> Unit,
    onEditTags: (TrackEntity) -> Unit
) {
    val track = state.currentTrack ?: return
    var showLyricsView by remember { mutableStateOf(false) }
    val lrcCandidates by viewModel.lrcCandidates.collectAsState()
    val isSearchingLyrics by viewModel.isSearchingLyrics.collectAsState()
    val parsedLines = remember(track.syncedLyricsLrc) {
        LyricsManager.parseLrc(track.syncedLyricsLrc)
    }
    val activeLineIndex = remember(parsedLines, state.positionMs) {
        LyricsManager.findActiveLineIndex(parsedLines, state.positionMs)
    }
    val lyricsListState = rememberLazyListState()

    LaunchedEffect(activeLineIndex, showLyricsView) {
        if (showLyricsView && activeLineIndex >= 0) {
            lyricsListState.animateScrollToItem((activeLineIndex - 2).coerceAtLeast(0))
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Lecture en cours", style = MaterialTheme.typography.titleSmall)
                Row {
                    IconButton(onClick = { showLyricsView = !showLyricsView }) {
                        Icon(
                            Icons.Default.Lyrics,
                            contentDescription = "Paroles LRC",
                            tint = if (showLyricsView) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { onEditTags(track) }) {
                        Icon(Icons.Default.Edit, contentDescription = "Éditer les tags")
                    }
                    IconButton(onClick = onOpenQueue) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "File d'attente")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (showLyricsView) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Paroles synchronisées (LRC)", fontWeight = FontWeight.SemiBold)
                            Row {
                                TextButton(
                                    onClick = { viewModel.autoSyncLyrics(track) },
                                    enabled = !isSearchingLyrics
                                ) {
                                    Text("Auto LRCLIB")
                                }
                                TextButton(
                                    onClick = { viewModel.searchLyricsOnline(track) },
                                    enabled = !isSearchingLyrics
                                ) {
                                    Text("Chercher")
                                }
                            }
                        }

                        if (lrcCandidates.isNotEmpty()) {
                            LazyColumn(modifier = Modifier.height(120.dp)) {
                                items(lrcCandidates, key = { it.id }) { cand ->
                                    TextButton(onClick = { viewModel.applyLyricsCandidate(track, cand) }) {
                                        Text("${cand.trackName} — ${cand.artistName} (${cand.durationSec.toInt()}s)")
                                    }
                                }
                            }
                            HorizontalDivider()
                        }

                        if (parsedLines.isNotEmpty()) {
                            LazyColumn(
                                state = lyricsListState,
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(parsedLines) { idx, line ->
                                    val isCurrent = idx == activeLineIndex
                                    Text(
                                        text = line.text.ifEmpty { "♪" },
                                        style = if (isCurrent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.playbackManager.seekTo(line.timestampMs) }
                                    )
                                }
                            }
                        } else if (!track.lyricsText.isNullOrBlank()) {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                item { Text(track.lyricsText) }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Aucune parole locale. Utilisez LRCLIB ci-dessus.")
                            }
                        }
                    }
                }
            } else {
                ArtworkBox(artwork = artwork, sizeDp = 260)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${track.artist} · ${track.album}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { viewModel.toggleFavorite(track) }) {
                    Icon(
                        imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favori",
                        tint = if (track.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val duration = state.durationMs.coerceAtLeast(1L)
            Slider(
                value = state.positionMs.coerceIn(0L, duration).toFloat(),
                onValueChange = { viewModel.playbackManager.seekTo(it.toLong()) },
                valueRange = 0f..duration.toFloat()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatDuration(state.positionMs), style = MaterialTheme.typography.labelSmall)
                Text(formatDuration(state.durationMs), style = MaterialTheme.typography.labelSmall)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.playbackManager.toggleShuffle() }) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "Aléatoire",
                        tint = if (state.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { viewModel.playbackManager.previous() }) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Précédent", modifier = Modifier.size(34.dp))
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(64.dp)
                        .clickable { viewModel.playbackManager.togglePlayPause() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Lecture/Pause",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                IconButton(onClick = { viewModel.playbackManager.next() }) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Suivant", modifier = Modifier.size(34.dp))
                }
                IconButton(onClick = { viewModel.playbackManager.cycleRepeatMode() }) {
                    val icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat
                    val active = state.repeatMode != Player.REPEAT_MODE_OFF
                    Icon(
                        icon,
                        contentDescription = "Répéter",
                        tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueBottomSheet(
    state: PlaybackUiState,
    onDismiss: () -> Unit,
    onSelectIndex: (Int) -> Unit,
    onMoveItem: (Int, Int) -> Unit,
    onRemoveItem: (Int) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                "File d'attente (${state.queue.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(state.queue) { idx, track ->
                    val isCurrent = idx == state.currentIndex
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectIndex(idx) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(track.title, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                                Text(track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                            IconButton(onClick = { onMoveItem(idx, idx - 1) }, enabled = idx > 0) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Monter")
                            }
                            IconButton(onClick = { onMoveItem(idx, idx + 1) }, enabled = idx < state.queue.lastIndex) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Descendre")
                            }
                            IconButton(onClick = { onRemoveItem(idx) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Retirer")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagEditorDialog(
    track: TrackEntity,
    onDismiss: () -> Unit,
    onSave: (AudioTagEdit) -> Unit
) {
    var title by remember { mutableStateOf(track.title) }
    var artist by remember { mutableStateOf(track.artist) }
    var album by remember { mutableStateOf(track.album) }
    var albumArtist by remember { mutableStateOf(track.albumArtist) }
    var genre by remember { mutableStateOf(track.genre) }
    var yearStr by remember { mutableStateOf(if (track.year > 0) track.year.toString() else "") }
    var trackNumStr by remember { mutableStateOf(if (track.trackNumber > 0) track.trackNumber.toString() else "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Éditer les tags") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Titre") }, singleLine = true)
                OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text("Artiste") }, singleLine = true)
                OutlinedTextField(value = album, onValueChange = { album = it }, label = { Text("Album") }, singleLine = true)
                OutlinedTextField(value = albumArtist, onValueChange = { albumArtist = it }, label = { Text("Artiste de l'album") }, singleLine = true)
                OutlinedTextField(value = genre, onValueChange = { genre = it }, label = { Text("Genre") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = yearStr,
                        onValueChange = { yearStr = it.filter(Char::isDigit).take(4) },
                        label = { Text("Année") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = trackNumStr,
                        onValueChange = { trackNumStr = it.filter(Char::isDigit).take(3) },
                        label = { Text("Piste #") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        AudioTagEdit(
                            title = title.trim().ifEmpty { track.title },
                            artist = artist.trim().ifEmpty { track.artist },
                            album = album.trim().ifEmpty { track.album },
                            albumArtist = albumArtist.trim(),
                            genre = genre.trim(),
                            year = yearStr.toIntOrNull() ?: 0,
                            trackNumber = trackNumStr.toIntOrNull() ?: 0
                        )
                    )
                }
            ) {
                Text("Enregistrer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}

@Composable
private fun AddToPlaylistDialog(
    track: TrackEntity,
    playlists: List<PlaylistEntity>,
    onDismiss: () -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onSelectPlaylist: (Long) -> Unit
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter « ${track.title} »") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                playlists.forEach { pl ->
                    TextButton(
                        onClick = { onSelectPlaylist(pl.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(pl.name, modifier = Modifier.fillMaxWidth())
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Ou nouvelle playlist...") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreateAndAdd(newName) },
                enabled = newName.isNotBlank()
            ) {
                Text("Créer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Fermer") }
        }
    )
}

@Composable
private fun TrackRow(
    track: TrackEntity,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEditTags: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MusicNote, contentDescription = null)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${track.artist} · ${formatDuration(track.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favori",
                    tint = if (track.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Lire ensuite") },
                        onClick = { menuExpanded = false; onPlayNext() }
                    )
                    DropdownMenuItem(
                        text = { Text("Ajouter à la file d'attente") },
                        onClick = { menuExpanded = false; onAddToQueue() }
                    )
                    DropdownMenuItem(
                        text = { Text("Ajouter à une playlist") },
                        onClick = { menuExpanded = false; onAddToPlaylist() }
                    )
                    DropdownMenuItem(
                        text = { Text("Éditer les tags") },
                        onClick = { menuExpanded = false; onEditTags() }
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onPlay: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.PlayArrow, contentDescription = "Lire")
        }
    }
}

@Composable
private fun ArtworkBox(artwork: Bitmap?, sizeDp: Int) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .size(sizeDp.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
    ) {
        if (artwork != null) {
            Image(
                bitmap = artwork.asImageBitmap(),
                contentDescription = "Pochette",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size((sizeDp / 2).dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%d:%02d".format(min, sec)
}
