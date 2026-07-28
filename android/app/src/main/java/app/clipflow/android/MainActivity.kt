package app.clipflow.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.clipflow.android.ui.ClipFlowScreen
import app.clipflow.android.ui.theme.ClipFlowTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val clipFlowViewModel: ClipFlowViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel = clipFlowViewModel
            val state by viewModel.state.collectAsStateWithLifecycle()
            ClipFlowTheme(darkTheme = state.darkTheme) {
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) {}

                LaunchedEffect(Unit) {
                    handleIncomingIntent(intent, viewModel)
                    if (
                        Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                ClipFlowScreen(
                    state = state,
                    onUrlChanged = viewModel::setUrl,
                    onAnalyze = viewModel::analyze,
                    onToggleSelected = viewModel::toggleSelected,
                    onSelectOnly = viewModel::selectOnly,
                    onToggleSelectAll = viewModel::toggleSelectAll,
                    onDownloadSelected = viewModel::downloadSelected,
                    onDownloadSegment = viewModel::downloadSegment,
                    onExtractAudio = viewModel::extractAudio,
                    onPause = viewModel::pause,
                    onResume = viewModel::resume,
                    onRemove = viewModel::remove,
                    onPlay = viewModel::playOutput,
                    onOpenFolder = viewModel::openOutputFolder,
                    onDeleteFile = viewModel::deleteOutput,
                    onPreferencesChanged = viewModel::updatePreferences,
                    onClipRangeChanged = viewModel::updateClipRange,
                    onOutputTreeChanged = viewModel::setOutputTree,
                    onUseDefaultOutput = viewModel::useDefaultOutput,
                    onCookieFileChanged = viewModel::importCookieFile,
                    onClearCookieFile = viewModel::clearCookieFile,
                    onErrorDismissed = viewModel::clearError,
                    onToggleSort = viewModel::toggleSort,
                    onToggleDarkTheme = viewModel::setDarkTheme,
                    onTogglePlaylist = viewModel::togglePlaylistExpanded,
                    onDownloadPlaylist = viewModel::downloadPlaylist,
                    onDismissUpdate = viewModel::dismissUpdate,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent, clipFlowViewModel)
    }

    private fun handleIncomingIntent(intent: Intent?, viewModel: ClipFlowViewModel) {
        sharedUrl(intent)?.let(viewModel::setUrl)
        // Emulator/automation: adb --ez app.clipflow.EXTRA_ANALYZE true
        // Delay past first frame so analyzing UI can paint before yt-dlp warms up.
        if (intent?.getBooleanExtra(EXTRA_ANALYZE, false) == true) {
            lifecycleScope.launch {
                delay(400)
                viewModel.analyze()
            }
        }
    }

    private fun sharedUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)
            ?.trim()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    companion object {
        const val EXTRA_ANALYZE = "app.clipflow.EXTRA_ANALYZE"
    }
}
