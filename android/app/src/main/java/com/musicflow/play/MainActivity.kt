package com.musicflow.play

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.musicflow.play.ui.AppTab
import com.musicflow.play.ui.TunedriftApp
import com.musicflow.play.ui.TunedriftViewModel

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ROUTE = "route"
    }

    private val viewModel: TunedriftViewModel by viewModels()
    private var hasAudioPermissionState by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val granted = hasRequiredAudioPermission()
            hasAudioPermissionState = granted
            if (granted) {
                viewModel.scanLibrary()
            }
        }

    private val folderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    contentResolver.takePersistableUriPermission(uri, flags)
                } catch (_: SecurityException) {
                }
                viewModel.addSafFolder(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hasAudioPermissionState = hasRequiredAudioPermission()

        setContent {
            TunedriftApp(
                viewModel = viewModel,
                hasAudioPermission = hasAudioPermissionState,
                onRequestPermissions = { requestAudioPermissions() },
                onPickSafFolder = { pickMusicFolder() },
                onOpenUrl = { openExternal(it) }
            )
        }

        handleIntentRoute(intent)
        if (hasAudioPermissionState) {
            viewModel.scanLibrary()
        } else {
            requestAudioPermissions()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentRoute(intent)
    }

    override fun onResume() {
        super.onResume()
        val nowGranted = hasRequiredAudioPermission()
        if (nowGranted && !hasAudioPermissionState) {
            viewModel.scanLibrary()
        }
        hasAudioPermissionState = nowGranted
    }

    private fun handleIntentRoute(intent: Intent?) {
        val widgetAction = intent?.getStringExtra(PlayerWidget.EXTRA_ACTION)
        if (widgetAction == "queue") {
            intent.removeExtra(PlayerWidget.EXTRA_ACTION)
            viewModel.setShowQueueSheet(true)
        }
        val route = intent?.getStringExtra(EXTRA_ROUTE) ?: return
        intent.removeExtra(EXTRA_ROUTE)
        when (route) {
            "library" -> viewModel.selectTab(AppTab.LIBRARY)
            "search", "download" -> viewModel.selectTab(AppTab.SEARCH)
            "playlists" -> viewModel.selectTab(AppTab.PLAYLISTS)
            "settings" -> viewModel.selectTab(AppTab.SETTINGS)
            "player" -> viewModel.setShowFullPlayer(true)
        }
    }

    fun hasRequiredAudioPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestAudioPermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                wanted.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                wanted.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                wanted.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (wanted.isNotEmpty()) {
            permissionLauncher.launch(wanted.toTypedArray())
        }
    }

    fun pickMusicFolder() {
        try {
            folderLauncher.launch(null)
        } catch (_: Exception) {
        }
    }

    fun openExternal(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
