package app.clipflow.android

import android.app.Application
import android.app.DownloadManager
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.clipflow.android.data.CookieFileStore
import app.clipflow.android.data.DownloadWorker
import app.clipflow.android.data.PersistedSession
import app.clipflow.android.data.SessionStore
import app.clipflow.android.data.UpdateChecker
import app.clipflow.android.data.YoutubeDlAnalyzer
import app.clipflow.android.model.ClipFlowUiState
import app.clipflow.android.model.ClipRange
import app.clipflow.android.model.DownloadPreferences
import app.clipflow.android.model.DownloadTaskState
import app.clipflow.android.model.MediaCandidate
import app.clipflow.android.model.RowKind
import app.clipflow.android.model.SortKey
import app.clipflow.android.model.SortState
import app.clipflow.android.model.TaskStatus
import app.clipflow.android.model.extractUrls
import app.clipflow.android.model.asClipDownload
import app.clipflow.android.model.preferredVisibleCandidate
import app.clipflow.android.model.sortCandidates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID

class ClipFlowViewModel(application: Application) : AndroidViewModel(application) {
    private data class PlaylistFolderDeleteResult(val success: Boolean, val removedFolder: Boolean)
    private data class DeleteTarget(
        val candidate: MediaCandidate,
        val task: DownloadTaskState,
        val failure: Throwable? = null,
    )
    private data class PendingDeleteApproval(
        val rootCandidateId: String,
        val targets: List<DeleteTarget>,
    )

    private val analyzer = YoutubeDlAnalyzer(application)
    private val cookieStore = CookieFileStore(application)
    private val sessionStore = SessionStore(application)
    private val updateChecker = UpdateChecker()
    private val workManager = WorkManager.getInstance(application)
    private val preferencesStore = application.getSharedPreferences("clipflow", 0)
    private val _state = MutableStateFlow(
        ClipFlowUiState(
            outputTreeUri = preferencesStore.getString("output_tree_uri", "").orEmpty(),
            outputLabel = preferencesStore.getString("output_label", "다운로드/ClipFlow")
                ?: "다운로드/ClipFlow",
            cookieLabel = cookieStore.label(),
            cookieEnabled = cookieStore.activeFile() != null,
            darkTheme = preferencesStore.getBoolean("dark_theme", false),
            sort = SortState(
                key = runCatching {
                    SortKey.valueOf(preferencesStore.getString("sort_key", SortKey.Latest.name)!!)
                }.getOrDefault(SortKey.Latest),
                descending = preferencesStore.getBoolean("sort_desc", true),
            ),
        ),
    )
    val state: StateFlow<ClipFlowUiState> = _state.asStateFlow()
    private val deleteApprovalChannel = Channel<IntentSender>(Channel.BUFFERED)
    val deleteApprovalRequests = deleteApprovalChannel.receiveAsFlow()

    /** All analyzed rows including hidden playlist children and multi-quality pools. */
    private var allRows: List<MediaCandidate> = emptyList()
    private val qualityPool = mutableMapOf<String, List<MediaCandidate>>()
    private val observedWorks = mutableSetOf<UUID>()
    private data class AnalysisRequest(
        val url: String,
        val autoDownload: Boolean,
        val clipRange: ClipRange,
    )

    private val analysisQueue = ArrayDeque<AnalysisRequest>()
    private var analysisJob: Job? = null
    private var persistJob: Job? = null
    private var pendingDeleteApproval: PendingDeleteApproval? = null
    private var createdCounter = System.currentTimeMillis()

    init {
        restoreSession()
        checkForUpdates()
    }

    fun setUrl(value: String) {
        _state.update { it.copy(url = value, error = "") }
        schedulePersist()
    }

    fun analyze() = startAnalysis(autoDownload = false)

    fun analyzeAndDownload() = startAnalysis(autoDownload = true)

    private fun startAnalysis(autoDownload: Boolean) {
        val urls = extractUrls(state.value.url)
        Log.i(LOG_TAG, "analyze() urls=$urls autoDownload=$autoDownload")
        if (urls.isEmpty()) {
            _state.update { it.copy(error = "http 또는 https URL을 입력하세요. 여러 개는 줄바꿈으로 넣을 수 있습니다.") }
            return
        }
        analysisQueue.clear()
        val clipRange = state.value.clipRange
        analysisQueue.addAll(urls.map { AnalysisRequest(it, autoDownload, clipRange) })
        _state.update {
            it.copy(
                analyzing = true,
                analysisMessage = if (urls.size > 1) {
                    "대기열 ${urls.size}개 분석 중"
                } else {
                    "분석 중"
                },
                analysisQueueRemaining = urls.size,
                error = "",
            )
        }
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch { drainAnalysisQueue() }
    }

    fun updatePreferences(value: DownloadPreferences) {
        recomputeVisible(preferences = value)
        schedulePersist()
    }

    fun updateClipRange(value: ClipRange) {
        _state.update { it.copy(clipRange = value) }
        schedulePersist()
    }

    fun toggleSort() {
        val current = state.value.sort
        val next = when {
            current.key == SortKey.Latest && current.descending -> SortState(SortKey.Latest, false)
            current.key == SortKey.Latest && !current.descending -> SortState(SortKey.Name, false)
            current.key == SortKey.Name && !current.descending -> SortState(SortKey.Name, true)
            else -> SortState(SortKey.Latest, true)
        }
        preferencesStore.edit {
            putString("sort_key", next.key.name)
            putBoolean("sort_desc", next.descending)
        }
        _state.update { it.copy(sort = next) }
        recomputeVisible()
        schedulePersist()
    }

    fun setDarkTheme(enabled: Boolean) {
        preferencesStore.edit { putBoolean("dark_theme", enabled) }
        _state.update { it.copy(darkTheme = enabled) }
        schedulePersist()
    }

    fun toggleSelected(candidateId: String) {
        _state.update { current ->
            val selected = current.selectedIds.toMutableSet()
            if (!selected.add(candidateId)) selected.remove(candidateId)
            current.copy(selectedIds = selected)
        }
        schedulePersist()
    }

    /** Long-press "선택": only the pressed card, not the whole list. */
    fun selectOnly(candidateId: String) {
        _state.update { it.copy(selectedIds = setOf(candidateId)) }
        schedulePersist()
    }

    fun clearSelection() {
        _state.update { it.copy(selectedIds = emptySet()) }
        schedulePersist()
    }

    fun toggleSelectAll() {
        _state.update { current ->
            val all = current.candidates
                .mapTo(mutableSetOf()) { it.id }
            current.copy(selectedIds = if (current.selectedIds.containsAll(all) && all.isNotEmpty()) emptySet() else all)
        }
        schedulePersist()
    }

    fun togglePlaylistExpanded(playlistId: String) {
        allRows = allRows.map {
            if (it.id == playlistId && it.kind == RowKind.Playlist) it.copy(expanded = !it.expanded) else it
        }
        recomputeVisible()
        schedulePersist()
    }

    fun downloadSelected() {
        state.value.candidates
            .filter { it.id in state.value.selectedIds && it.kind != RowKind.Playlist && !it.childLoading }
            .forEach { candidate ->
                enqueueDownload(candidate, candidate.clipRange.takeIf { it.isSet } ?: state.value.clipRange)
            }
    }

    fun resumePlaylist(playlistId: String) {
        allRows.filter {
            it.parentId == playlistId && it.kind == RowKind.PlaylistChild && !it.childLoading && !it.isDerivedDownload
        }
            .filter { it.analysisError.isBlank() && it.formatId != "failed" }
            .filter { state.value.tasks[it.id]?.status != TaskStatus.Completed }
            .forEach { enqueueDownload(it, ClipRange()) }
    }

    fun pausePlaylist(playlistId: String) {
        allRows.asSequence()
            .filter { it.parentId == playlistId && it.kind == RowKind.PlaylistChild && !it.isDerivedDownload }
            .map { it.id }
            .filter { state.value.tasks[it]?.status in ACTIVE_TASK_STATUSES }
            .toList()
            .forEach(::pause)
    }

    fun download(candidate: MediaCandidate) = enqueueDownload(candidate, state.value.clipRange)

    fun downloadSegment(candidate: MediaCandidate, clipRange: ClipRange) {
        if (!clipRange.isSet) return
        val order = nextOrder()
        val segment = candidate.asClipDownload(
            range = clipRange,
            newId = "${candidate.id}-clip-$order",
            createdOrder = order,
        ).copy(isDerivedDownload = true)
        allRows = allRows + segment
        qualityPool[segment.id] = listOf(segment)
        recomputeVisible()
        enqueueDownload(segment, segment.clipRange)
        schedulePersist()
    }

    fun extractAudio(candidate: MediaCandidate, format: String) {
        val normalizedFormat = format.lowercase().takeIf { it in setOf("wav", "mp3", "aac") } ?: return
        val current = state.value
        val taskKey = hash("${candidate.sourceUrl}|${candidate.playlistTitle}|audio|$normalizedFormat")
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_URL to candidate.sourceUrl,
                    DownloadWorker.KEY_FORMAT to "bestaudio/best",
                    DownloadWorker.KEY_OUTPUT_FORMAT to normalizedFormat,
                    DownloadWorker.KEY_TREE_URI to current.outputTreeUri,
                    DownloadWorker.KEY_CONCURRENCY to current.preferences.concurrency,
                    DownloadWorker.KEY_START to -1,
                    DownloadWorker.KEY_END to -1,
                    DownloadWorker.KEY_EXACT_CUT to false,
                    DownloadWorker.KEY_AUDIO_FORMAT to normalizedFormat,
                    DownloadWorker.KEY_TASK_KEY to taskKey,
                    DownloadWorker.KEY_PLAYLIST_TITLE to candidate.playlistTitle,
                ),
            )
            .addTag(DOWNLOAD_TAG)
            .build()
        workManager.enqueueUniqueWork("clipflow-audio-$taskKey", ExistingWorkPolicy.REPLACE, request)
        observeAuxiliaryWork(request.id)
    }

    fun pause(candidateId: String) {
        val task = state.value.tasks[candidateId] ?: return
        task.workId.toUuidOrNull()?.let(workManager::cancelWorkById)
        updateTask(candidateId) { it.copy(status = TaskStatus.Paused, detail = "일시정지") }
        schedulePersist()
    }

    fun resume(candidate: MediaCandidate) = enqueueDownload(
        candidate,
        candidate.clipRange.takeIf { it.isSet } ?: ClipRange(),
    )

    /**
     * List remove (desktop remove_row / paused "다운로드 삭제"):
     * cancel worker, wipe partial work dir, drop row. Does not delete a
     * completed public Download/ClipFlow file unless outputUri is set and
     * caller intends full wipe — for paused in-progress, only temps are wiped.
     */
    fun remove(candidateId: String) {
        val task = state.value.tasks[candidateId]
        task?.workId?.toUuidOrNull()?.let(workManager::cancelWorkById)
        val row = allRows.firstOrNull { it.id == candidateId }
        val related = if (row?.kind == RowKind.Playlist) {
            allRows.filter { it.id == candidateId || it.parentId == candidateId }
        } else {
            listOfNotNull(row)
        }
        related.forEach { candidate ->
            val relatedTask = state.value.tasks[candidate.id]
            relatedTask?.workId?.toUuidOrNull()?.let(workManager::cancelWorkById)
            // Paused/failed mid-download: drop partials. Completed public file stays
            // unless deleteOutput was used — list remove alone matches desktop.
            wipeWorkDir(candidate, relatedTask?.taskKey.orEmpty())
        }
        allRows = if (row?.kind == RowKind.Playlist) {
            allRows.filterNot { it.id == candidateId || it.parentId == candidateId }
        } else {
            allRows.filterNot { it.id == candidateId }
        }
        qualityPool.remove(candidateId)
        related.forEach { qualityPool.remove(it.id) }
        val removedIds = related.map { it.id }.toSet() + candidateId
        val removedSource = row?.sourceUrl.orEmpty()
        _state.update { current ->
            // Drop the URL field when the last card for that link is gone.
            val stillHasSource = allRows.any { it.sourceUrl == removedSource }
            current.copy(
                selectedIds = current.selectedIds - removedIds,
                tasks = current.tasks.filterKeys { it !in removedIds },
                url = if (!stillHasSource && removedSource.isNotBlank() &&
                    (current.url == removedSource || current.url.contains(removedSource) ||
                        removedSource.contains(current.url.trim()))
                ) {
                    ""
                } else {
                    current.url
                },
            )
        }
        recomputeVisible()
        schedulePersist()
    }

    fun setOutputTree(uri: Uri?) {
        if (uri == null) return
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val label = DocumentFile.fromTreeUri(getApplication(), uri)?.name ?: "선택한 폴더"
        preferencesStore.edit {
            putString("output_tree_uri", uri.toString())
            putString("output_label", label)
        }
        _state.update { it.copy(outputTreeUri = uri.toString(), outputLabel = label) }
        schedulePersist()
    }

    fun useDefaultOutput() {
        preferencesStore.edit {
            remove("output_tree_uri")
            putString("output_label", "다운로드/ClipFlow")
        }
        _state.update { it.copy(outputTreeUri = "", outputLabel = "다운로드/ClipFlow") }
        schedulePersist()
    }

    fun importCookieFile(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { cookieStore.import(uri) } }
                .onSuccess { label ->
                    _state.update { it.copy(cookieLabel = label, cookieEnabled = true, error = "") }
                    schedulePersist()
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "쿠키 파일을 가져오지 못했습니다.") }
                }
        }
    }

    fun clearCookieFile() {
        cookieStore.clear()
        _state.update { it.copy(cookieLabel = "쿠키 자동", cookieEnabled = false) }
        schedulePersist()
    }

    fun clearError() {
        _state.update { it.copy(error = "") }
    }

    fun dismissUpdate() {
        _state.update { it.copy(updateMessage = "", updateUrl = "") }
    }

    fun playOutput(candidateId: String) {
        val task = state.value.tasks[candidateId] ?: return
        val uri = task.outputUri.takeIf(String::isNotBlank)?.toUri() ?: return
        val mimeType = when (task.outputName.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            else -> "video/mp4"
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { getApplication<Application>().startActivity(intent) }
            .onFailure { _state.update { it.copy(error = "재생할 앱을 찾을 수 없습니다.") } }
    }

    fun openOutputFolder(candidateId: String) {
        val row = allRows.firstOrNull { it.id == candidateId }
        val task = if (row?.kind == RowKind.Playlist) {
            allRows.asSequence()
                .filter { it.parentId == candidateId }
                .mapNotNull { state.value.tasks[it.id] }
                .firstOrNull { it.outputUri.isNotBlank() }
        } else {
            state.value.tasks[candidateId]
        } ?: return
        val treeUri = state.value.outputTreeUri.takeIf(String::isNotBlank)
        val intent = if (treeUri != null && task.outputUri.contains("/tree/")) {
            Intent(Intent.ACTION_VIEW).setDataAndType(treeUri.toUri(), DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { getApplication<Application>().startActivity(intent) }
            .onFailure { _state.update { it.copy(error = "폴더를 열 수 없습니다.") } }
    }

    /** Delete saved output and remove its card, matching desktop delete_file_for_row. */
    fun deleteOutput(candidateId: String) {
        val row = allRows.firstOrNull { it.id == candidateId } ?: return
        val targets = if (row.kind == RowKind.Playlist) {
            allRows.filter { it.parentId == candidateId }
        } else {
            listOf(row)
        }.mapNotNull { candidate ->
            state.value.tasks[candidate.id]
                ?.takeIf { it.outputUri.isNotBlank() }
                ?.let { candidate to it }
        }
        if (targets.isEmpty() && row.kind != RowKind.Playlist) {
            remove(row.id)
            return
        }

        viewModelScope.launch {
            val (deletedIds, blockedTargets) = withContext(Dispatchers.IO) {
                val deleted = mutableSetOf<String>()
                val blocked = mutableListOf<DeleteTarget>()
                targets.forEach { (candidate, task) ->
                    val uri = task.outputUri.toUri()
                    val directDelete = runCatching {
                        getApplication<Application>().contentResolver.delete(uri, null, null) > 0
                    }
                    val documentDeleted = if (directDelete.exceptionOrNull() is SecurityException) {
                            false
                        } else {
                            runCatching {
                                DocumentFile.fromSingleUri(getApplication(), uri)?.delete() == true
                            }.getOrDefault(false)
                        }
                    val outputDeleted = directDelete.getOrDefault(false) || documentDeleted || !mediaUriExists(uri)
                    if (outputDeleted) {
                        deleted += candidate.id
                    } else {
                        blocked += DeleteTarget(candidate, task, directDelete.exceptionOrNull())
                    }
                }
                deleted to blocked
            }

            if (deletedIds.isNotEmpty()) {
                finishDeletedTargets(
                    rootCandidateId = candidateId,
                    targets = targets.map { DeleteTarget(it.first, it.second) },
                    deletedIds = deletedIds,
                    deletePlaylistFolder = blockedTargets.isEmpty(),
                )
            }

            if (blockedTargets.isNotEmpty()) {
                requestDeleteApproval(candidateId, blockedTargets)
            } else if (deletedIds.size != targets.size) {
                _state.update { it.copy(error = "저장 파일을 지우지 못했습니다.") }
            }
        }
    }

    fun completeDeleteApproval(approved: Boolean) {
        val pending = pendingDeleteApproval ?: return
        pendingDeleteApproval = null
        if (!approved) {
            _state.update { it.copy(error = "파일 삭제가 취소되었습니다.") }
            return
        }

        viewModelScope.launch {
            val deletedIds = withContext(Dispatchers.IO) {
                pending.targets.mapNotNull { target ->
                    val uri = target.task.outputUri.toUri()
                    val deleted = runCatching {
                        getApplication<Application>().contentResolver.delete(uri, null, null) > 0
                    }.getOrDefault(false) || !mediaUriExists(uri)
                    target.candidate.id.takeIf { deleted }
                }.toSet()
            }
            finishDeletedTargets(
                rootCandidateId = pending.rootCandidateId,
                targets = pending.targets,
                deletedIds = deletedIds,
                deletePlaylistFolder = deletedIds.size == pending.targets.size,
            )
            if (deletedIds.size != pending.targets.size) {
                _state.update { it.copy(error = "승인된 파일 일부를 지우지 못했습니다.") }
            }
        }
    }

    private suspend fun requestDeleteApproval(rootCandidateId: String, targets: List<DeleteTarget>) {
        val mediaTargets = targets.filter { it.task.outputUri.toUri().authority == MediaStore.AUTHORITY }
        val senderResult = runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mediaTargets.isNotEmpty() -> {
                    MediaStore.createDeleteRequest(
                        getApplication<Application>().contentResolver,
                        mediaTargets.map { target ->
                            val storedUri = target.task.outputUri.toUri()
                            val id = ContentUris.parseId(storedUri)
                            when (target.task.outputName.substringAfterLast('.', "").lowercase()) {
                                "mp3", "wav", "aac", "m4a" -> MediaStore.Audio.Media.getContentUri(
                                    MediaStore.VOLUME_EXTERNAL_PRIMARY,
                                    id,
                                )
                                else -> MediaStore.Video.Media.getContentUri(
                                    MediaStore.VOLUME_EXTERNAL_PRIMARY,
                                    id,
                                )
                            }
                        },
                    ).intentSender
                }
                else -> mediaTargets.firstNotNullOfOrNull { target ->
                    (target.failure as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender
                }
            }
        }
        senderResult.exceptionOrNull()?.let { error ->
            Log.e(LOG_TAG, "Unable to create MediaStore delete approval for ${mediaTargets.map { it.task.outputUri }}", error)
        }
        val sender = senderResult.getOrNull()
        if (sender == null || mediaTargets.size != targets.size) {
            _state.update { it.copy(error = "저장 위치의 삭제 권한을 확인하세요.") }
            return
        }
        pendingDeleteApproval = PendingDeleteApproval(rootCandidateId, mediaTargets)
        deleteApprovalChannel.send(sender)
    }

    private suspend fun finishDeletedTargets(
        rootCandidateId: String,
        targets: List<DeleteTarget>,
        deletedIds: Set<String>,
        deletePlaylistFolder: Boolean,
    ) {
        if (deletedIds.isEmpty()) return
        val root = allRows.firstOrNull { it.id == rootCandidateId }
        targets.filter { it.candidate.id in deletedIds }.forEach { target ->
            target.task.workId.toUuidOrNull()?.let(workManager::cancelWorkById)
            wipeWorkDir(target.candidate, target.task.taskKey)
        }
        val folderResult = if (root?.kind == RowKind.Playlist && deletePlaylistFolder) {
            withContext(Dispatchers.IO) { deletePlaylistOutputFolder(root.title) }
        } else {
            PlaylistFolderDeleteResult(success = true, removedFolder = false)
        }

        if (root?.kind == RowKind.Playlist && deletePlaylistFolder && deletedIds.size == targets.size) {
            remove(root.id)
        } else {
            val parentIds = targets.asSequence()
                .map { it.candidate }
                .filter { it.id in deletedIds }
                .map { it.parentId }
                .filter(String::isNotBlank)
                .toSet()
            deletedIds.forEach(::remove)
            parentIds.forEach(::refreshPlaylistParent)
        }
        if (!folderResult.success) {
            _state.update { it.copy(error = "파일은 삭제했지만 재생목록 폴더를 지우지 못했습니다.") }
        }
    }

    private fun mediaUriExists(uri: Uri): Boolean = runCatching {
        getApplication<Application>().contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns._ID),
            null,
            null,
            null,
        )?.use { it.moveToFirst() } ?: false
    }.getOrDefault(true)

    private fun deletePlaylistOutputFolder(title: String): PlaylistFolderDeleteResult {
        val folderName = title
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .trimEnd('.')
            .take(80)
        if (folderName.isBlank()) return PlaylistFolderDeleteResult(success = true, removedFolder = false)

        val application = getApplication<Application>()
        val treeUri = state.value.outputTreeUri.takeIf(String::isNotBlank)
        if (treeUri != null) {
            val root = DocumentFile.fromTreeUri(application, treeUri.toUri())
                ?: return PlaylistFolderDeleteResult(success = false, removedFolder = false)
            if (root.name == folderName) {
                return PlaylistFolderDeleteResult(success = true, removedFolder = false)
            }
            val playlistFolder = root.findFile(folderName)
                ?: return PlaylistFolderDeleteResult(success = true, removedFolder = true)
            val deleted = playlistFolder.isDirectory && playlistFolder.delete()
            return PlaylistFolderDeleteResult(success = deleted, removedFolder = deleted)
        }

        val resolver = application.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/ClipFlow/$folderName/"
        var mediaDeleted = true
        runCatching {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(relativePath),
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val uris = buildList {
                    while (cursor.moveToNext()) {
                        add(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn)))
                    }
                }
                uris.forEach { uri ->
                    if (resolver.delete(uri, null, null) <= 0) mediaDeleted = false
                }
            }
        }.onFailure { mediaDeleted = false }

        val folder = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "ClipFlow/$folderName",
        )
        val directoryDeleted = !folder.exists() || folder.deleteRecursively()
        val deleted = mediaDeleted && directoryDeleted
        return PlaylistFolderDeleteResult(success = deleted, removedFolder = deleted)
    }

    private fun refreshPlaylistParent(parentId: String) {
        val childCount = allRows.count {
            it.parentId == parentId && it.kind == RowKind.PlaylistChild && !it.childLoading && !it.isDerivedDownload
        }
        allRows = fillMissingPlaylistThumbnails(allRows.map {
            if (it.id == parentId && it.kind == RowKind.Playlist) it.copy(itemCount = childCount) else it
        })
        recomputeVisible()
        schedulePersist()
    }

    private fun fillMissingPlaylistThumbnails(rows: List<MediaCandidate>): List<MediaCandidate> {
        val childThumbnails = rows.asSequence()
            .filter { it.kind == RowKind.PlaylistChild && it.parentId.isNotBlank() && it.thumbnailUrl.isNotBlank() }
            .groupBy { it.parentId }
            .mapValues { (_, children) ->
                children.minByOrNull { it.playlistIndex }?.thumbnailUrl.orEmpty()
            }
        return rows.map { row ->
            if (row.kind == RowKind.Playlist && row.thumbnailUrl.isBlank()) {
                row.copy(thumbnailUrl = childThumbnails[row.id].orEmpty())
            } else {
                row
            }
        }
    }

    private fun wipeWorkDir(candidate: MediaCandidate, knownTaskKey: String = "") {
        val keys = buildSet {
            if (knownTaskKey.isNotBlank()) add(knownTaskKey)
            add(hash("${candidate.sourceUrl}|${candidate.formatSelector}|${candidate.route}|${state.value.preferences.format}|${state.value.clipRange}"))
        }
        val root = File(getApplication<Application>().filesDir, "downloads")
        keys.forEach { key ->
            val dir = File(root, key)
            if (dir.exists()) runCatching { dir.deleteRecursively() }
        }
    }

    private suspend fun drainAnalysisQueue() {
        while (analysisQueue.isNotEmpty()) {
            val request = analysisQueue.removeFirst()
            val url = request.url
            val remaining = analysisQueue.size
            _state.update {
                it.copy(
                    analyzing = true,
                    analysisMessage = if (remaining > 0) "분석 중 · 남은 ${remaining + 1}개" else "분석 중",
                    analysisQueueRemaining = remaining + 1,
                )
            }
            val analysis = runCatching {
                withContext(Dispatchers.IO) {
                    analyzeOne(url, request.clipRange) { child ->
                        if (request.autoDownload) enqueueDownload(child, request.clipRange)
                    }
                }
            }
            analysis.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
            }
            analysis
                .onSuccess { analyzedPlaylist ->
                    Log.i(LOG_TAG, "analyzeOne ok url=$url candidates=${state.value.candidates.size}")
                    if (request.autoDownload && !analyzedPlaylist) {
                        allRows.firstOrNull {
                            it.sourceUrl == url &&
                                it.kind != RowKind.Playlist &&
                                !it.childLoading &&
                                it.formatId !in setOf("loading", "failed")
                        }?.let { enqueueDownload(it, request.clipRange) }
                    }
                }
                .onFailure { error ->
                    Log.e(LOG_TAG, "analyzeOne failed url=$url: ${error.message}", error)
                    _state.update {
                        it.copy(error = listOfNotNull(it.error.takeIf(String::isNotBlank), error.message).joinToString("\n"))
                    }
                }
        }
        _state.update {
            it.copy(analyzing = false, analysisMessage = "", analysisQueueRemaining = 0)
        }
        schedulePersist()
    }

    private fun analyzeOne(
        url: String,
        clipRange: ClipRange,
        onPlaylistChildReady: (MediaCandidate) -> Unit,
    ): Boolean {
        if (analyzer.looksLikePlaylist(url)) {
            val shell = analyzer.analyzePlaylistShell(url)
            val parent = shell.candidates.first().copy(createdOrder = nextOrder())
            allRows = listOf(parent) + allRows.filterNot { it.id == parent.id || it.parentId == parent.id }
            recomputeVisible(selectId = parent.id)
            shell.playlistEntries.forEach { entry ->
                val loadingId = "${parent.id}-loading-${entry.index}"
                val loading = MediaCandidate(
                    id = loadingId,
                    sourceUrl = entry.url,
                    mediaUrl = "",
                    title = entry.title,
                    uploader = "",
                    thumbnailUrl = entry.thumbnailUrl,
                    formatId = "loading",
                    extension = "",
                    width = 0,
                    height = 0,
                    fps = 0,
                    videoCodec = "",
                    audioCodec = "",
                    dynamicRange = "",
                    sizeBytes = 0,
                    durationSeconds = entry.durationSeconds,
                    isManifest = false,
                    kind = RowKind.PlaylistChild,
                    parentId = parent.id,
                    playlistIndex = entry.index,
                    createdOrder = nextOrder(),
                    childLoading = true,
                    route = "playlist",
                )
                allRows = allRows + loading
                recomputeVisible()
                runCatching {
                    if (entry.url.isBlank()) {
                        error("재생할 수 없는 재생목록 항목입니다.")
                    }
                    val analyzed = analyzer.analyze(entry.url, allowBrowserFallback = true)
                    val preferred = preferredVisibleCandidate(analyzed.candidates, state.value.preferences)
                        ?: analyzed.candidates.first()
                    val baseChild = preferred.copy(
                        id = "${parent.id}-child-${entry.index}-${preferred.formatId}",
                        title = preferred.title.ifBlank { entry.title },
                        kind = RowKind.PlaylistChild,
                        parentId = parent.id,
                        playlistIndex = entry.index,
                        createdOrder = loading.createdOrder,
                        childLoading = false,
                        route = analyzed.route,
                        playlistTitle = parent.title,
                    )
                    val child = if (clipRange.isSet) {
                        baseChild.asClipDownload(clipRange, baseChild.id, baseChild.createdOrder)
                    } else {
                        baseChild
                    }
                    qualityPool[child.id] = analyzed.candidates.map { quality ->
                        val childQuality = quality.copy(
                            parentId = parent.id,
                            kind = RowKind.PlaylistChild,
                            playlistIndex = entry.index,
                            playlistTitle = parent.title,
                        )
                        if (clipRange.isSet) {
                            childQuality.asClipDownload(clipRange, childQuality.id, childQuality.createdOrder)
                        } else {
                            childQuality
                        }
                    }
                    allRows = fillMissingPlaylistThumbnails(
                        allRows.map { if (it.id == loadingId) child else it },
                    )
                    recomputeVisible()
                    onPlaylistChildReady(child)
                }.onFailure { error ->
                    allRows = allRows.map {
                        if (it.id == loadingId) {
                            it.copy(
                                childLoading = false,
                                title = entry.title,
                                sourceUrl = entry.url.ifBlank { parent.sourceUrl },
                                formatId = "failed",
                                playlistTitle = parent.title,
                                analysisError = error.message ?: "영상 분석에 실패했습니다.",
                            )
                        } else it
                    }
                    recomputeVisible()
                }
            }
            allRows = allRows.map {
                if (it.id == parent.id) it.copy(itemCount = shell.playlistEntries.size) else it
            }
            recomputeVisible()
            return true
        }

        val result = analyzer.analyze(url)
        val preferred = preferredVisibleCandidate(result.candidates, state.value.preferences)
            ?: result.candidates.first()
        val baseRow = preferred.copy(
            id = preferred.id.ifBlank { hash(url) },
            createdOrder = nextOrder(),
            route = result.route,
        )
        val row = if (clipRange.isSet) {
            baseRow.asClipDownload(clipRange, baseRow.id, baseRow.createdOrder)
        } else {
            baseRow
        }
        qualityPool[row.id] = result.candidates.map { quality ->
            if (clipRange.isSet) {
                quality.asClipDownload(clipRange, quality.id, quality.createdOrder)
            } else {
                quality
            }
        }
        // Replace same source URL single row, otherwise prepend.
        allRows = listOf(row) + allRows.filterNot {
            it.kind != RowKind.PlaylistChild && it.sourceUrl == url
        }
        recomputeVisible(selectId = row.id)
        return false
    }

    private fun enqueueDownload(candidate: MediaCandidate, clipRange: ClipRange = state.value.clipRange) {
        if (candidate.kind == RowKind.Playlist || candidate.childLoading) return
        enqueueDownloadNow(candidate, clipRange)
    }

    private fun enqueueDownloadNow(candidate: MediaCandidate, clipRange: ClipRange = state.value.clipRange) {
        val current = state.value
        val outputFormat = current.preferences.format.lowercase().ifBlank { "mp4" }
        // Task key ignores volatile media tokens so resume reuses the same work folder.
        val taskKey = hash("${candidate.sourceUrl}|${candidate.playlistTitle}|${candidate.formatSelector}|${candidate.route}|$outputFormat|$clipRange")
        // YouTube/ytdlp: always download from the page URL so yt-dlp merges video+audio.
        // Never feed a bare googlevideo progressive/audio URL as "direct".
        val useDirect = !candidate.isYoutubeSource && candidate.mediaUrl.isNotBlank() && (
            candidate.prefersDirectUrl ||
                candidate.route in setOf("chzzk", "browser", "direct") ||
                candidate.isManifest
            )
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_URL to candidate.sourceUrl,
                    DownloadWorker.KEY_DIRECT_URL to candidate.mediaUrl.takeIf {
                        useDirect || candidate.formatId == "direct"
                    }.orEmpty(),
                    DownloadWorker.KEY_PREFER_DIRECT to useDirect,
                    DownloadWorker.KEY_REFERER to candidate.sourceUrl,
                    DownloadWorker.KEY_FORMAT to candidate.formatSelector,
                    DownloadWorker.KEY_OUTPUT_FORMAT to outputFormat,
                    DownloadWorker.KEY_TITLE to candidate.title,
                    DownloadWorker.KEY_EXPECTED_BYTES to candidate.sizeBytes,
                    DownloadWorker.KEY_TREE_URI to current.outputTreeUri,
                    DownloadWorker.KEY_CONCURRENCY to current.preferences.concurrency,
                    DownloadWorker.KEY_START to (clipRange.startSeconds ?: -1),
                    DownloadWorker.KEY_END to (clipRange.endSeconds ?: -1),
                    DownloadWorker.KEY_EXACT_CUT to clipRange.exact,
                    DownloadWorker.KEY_TASK_KEY to taskKey,
                    DownloadWorker.KEY_PLAYLIST_TITLE to candidate.playlistTitle,
                ),
            )
            .addTag(DOWNLOAD_TAG)
            .build()
        workManager.enqueueUniqueWork("clipflow-$taskKey", ExistingWorkPolicy.REPLACE, request)
        updateTask(candidate.id) {
            DownloadTaskState(
                workId = request.id.toString(),
                status = TaskStatus.Queued,
                detail = "대기 중",
                taskKey = taskKey,
            )
        }
        observeWork(candidate.id, request.id)
        schedulePersist()
    }

    private fun observeWork(candidateId: String, workId: UUID) {
        if (!observedWorks.add(workId)) return
        viewModelScope.launch {
            while (isActive) {
                val info = withContext(Dispatchers.IO) { runCatching { workManager.getWorkInfoById(workId).get() }.getOrNull() }
                if (info == null) {
                    updateTask(candidateId) { old ->
                        old.copy(status = TaskStatus.Paused, detail = "작업 정보 없음 · 다시 시작 가능")
                    }
                    schedulePersist()
                    break
                }
                val progress = info.progress.getInt(DownloadWorker.PROGRESS, 0)
                val detail = info.progress.getString(DownloadWorker.DETAIL).orEmpty()
                val finishing = info.progress.getBoolean(DownloadWorker.FINISHING, false)
                val status = when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> TaskStatus.Queued
                    WorkInfo.State.RUNNING -> if (finishing) TaskStatus.Finishing else TaskStatus.Downloading
                    WorkInfo.State.SUCCEEDED -> TaskStatus.Completed
                    WorkInfo.State.FAILED -> TaskStatus.Failed
                    WorkInfo.State.CANCELLED -> state.value.tasks[candidateId]?.status
                        ?.takeIf { it == TaskStatus.Paused } ?: TaskStatus.Failed
                }
                val finalDetail = when (status) {
                    TaskStatus.Completed -> "완료"
                    TaskStatus.Failed -> info.outputData.getString(DownloadWorker.ERROR).orEmpty()
                        .ifBlank { "다운로드 실패" }
                    else -> detail.ifBlank { state.value.tasks[candidateId]?.detail.orEmpty() }
                }
                updateTask(candidateId) { old ->
                    old.copy(
                        status = status,
                        progress = if (status == TaskStatus.Completed) 100 else progress,
                        detail = finalDetail,
                        outputName = info.outputData.getString(DownloadWorker.OUTPUT_NAME).orEmpty()
                            .ifBlank { old.outputName },
                        outputUri = info.outputData.getString(DownloadWorker.OUTPUT_URI).orEmpty()
                            .ifBlank { old.outputUri },
                    )
                }
                if (status == TaskStatus.Completed) {
                    applyActualMediaMetadata(
                        candidateId = candidateId,
                        sizeBytes = info.outputData.getLong(DownloadWorker.OUTPUT_BYTES, 0L),
                        durationSeconds = info.outputData.getInt(DownloadWorker.OUTPUT_DURATION_SECONDS, 0),
                        formatSelector = info.outputData.getString(DownloadWorker.OUTPUT_FORMAT_SELECTOR).orEmpty(),
                    )
                }
                if (info.state.isFinished) {
                    schedulePersist()
                    break
                }
                delay(300)
            }
            observedWorks.remove(workId)
        }
    }

    private fun applyActualMediaMetadata(
        candidateId: String,
        sizeBytes: Long,
        durationSeconds: Int,
        formatSelector: String,
    ) {
        if (sizeBytes <= 0L && durationSeconds <= 0) return
        val row = allRows.firstOrNull { it.id == candidateId } ?: return
        fun corrected(candidate: MediaCandidate): MediaCandidate = candidate.copy(
            sizeBytes = sizeBytes.takeIf { it > 0 } ?: candidate.sizeBytes,
            durationSeconds = durationSeconds.takeIf { it > 0 } ?: candidate.durationSeconds,
        )

        val pool = qualityPool[candidateId]
        var correctedPoolCandidate = false
        if (!pool.isNullOrEmpty()) {
            qualityPool[candidateId] = pool.map { candidate ->
                val matches = !correctedPoolCandidate && (
                    candidate.formatSelector == formatSelector ||
                        formatSelector.isBlank() && pool.size == 1
                    )
                if (matches) {
                    correctedPoolCandidate = true
                    corrected(candidate)
                } else {
                    candidate
                }
            }
        }

        if (row.formatSelector == formatSelector || !correctedPoolCandidate) {
            allRows = allRows.map { candidate ->
                if (candidate.id == candidateId) corrected(candidate) else candidate
            }
        }
        recomputeVisible()
    }

    private fun observeAuxiliaryWork(workId: UUID) {
        viewModelScope.launch {
            while (isActive) {
                val info = withContext(Dispatchers.IO) { runCatching { workManager.getWorkInfoById(workId).get() }.getOrNull() }
                if (info == null) {
                    _state.update { it.copy(error = "음원 추출 작업 정보를 찾을 수 없습니다.") }
                    return@launch
                }
                if (!info.state.isFinished) {
                    delay(250)
                    continue
                }
                if (info.state == WorkInfo.State.FAILED) {
                    val message = info.outputData.getString(DownloadWorker.ERROR).orEmpty()
                        .ifBlank { "음원 추출에 실패했습니다." }
                    _state.update { it.copy(error = message) }
                }
                return@launch
            }
        }
    }

    private fun recomputeVisible(preferences: DownloadPreferences = state.value.preferences, selectId: String? = null) {
        // Expand quality pools into preference-filtered primary rows for non-playlist items.
        val displayRows = allRows.map { row ->
            if (row.kind == RowKind.Playlist || row.childLoading || row.kind == RowKind.PlaylistChild && row.formatId == "failed") {
                row
            } else {
                val pool = qualityPool[row.id]
                if (pool.isNullOrEmpty()) row
                else preferredVisibleCandidate(pool, preferences)?.copy(
                    id = row.id,
                    kind = row.kind,
                    parentId = row.parentId,
                    playlistIndex = row.playlistIndex,
                    createdOrder = row.createdOrder,
                    expanded = row.expanded,
                    itemCount = row.itemCount,
                    playlistTitle = row.playlistTitle,
                    analysisError = row.analysisError,
                ) ?: row
            }
        }
        _state.update { current ->
            val rowsWithPlaylistTotals = displayRows.map { row ->
                if (row.kind != RowKind.Playlist) return@map row
                val children = displayRows.filter {
                    it.parentId == row.id &&
                        it.kind == RowKind.PlaylistChild &&
                        !it.isDerivedDownload &&
                        !it.childLoading &&
                        it.analysisError.isBlank()
                }
                val allDownloaded = row.itemCount > 0 &&
                    children.size == row.itemCount &&
                    children.all { child ->
                        current.tasks[child.id]?.status == TaskStatus.Completed && child.sizeBytes > 0L
                    }
                row.copy(sizeBytes = if (allDownloaded) children.sumOf { it.sizeBytes } else 0L)
            }
            val sorted = sortCandidates(rowsWithPlaylistTotals, current.sort)
            val tasks = withPlaylistTaskAggregates(rowsWithPlaylistTotals, current.tasks)
            val selected = when {
                // Auto-select only the newly analyzed card; never accumulate the whole list.
                selectId != null -> setOf(selectId)
                else -> current.selectedIds.intersect(sorted.mapTo(mutableSetOf()) { it.id })
            }
            current.copy(
                preferences = preferences,
                candidates = sorted,
                selectedIds = selected,
                tasks = tasks,
            )
        }
    }

    private fun withPlaylistTaskAggregates(
        rows: List<MediaCandidate>,
        tasks: Map<String, DownloadTaskState>,
    ): Map<String, DownloadTaskState> {
        var result = tasks
        rows.filter { it.kind == RowKind.Playlist }.forEach { parent ->
            val children = rows.filter {
                it.parentId == parent.id && it.kind == RowKind.PlaylistChild && !it.isDerivedDownload &&
                    !it.childLoading && it.analysisError.isBlank()
            }
            val childTasks = children.mapNotNull { tasks[it.id] }
            val active = childTasks.filter { it.status in ACTIVE_TASK_STATUSES }
            val completed = childTasks.count { it.status == TaskStatus.Completed }
            val status = when {
                active.any { it.status == TaskStatus.Finishing } -> TaskStatus.Finishing
                active.any { it.status == TaskStatus.Downloading } -> TaskStatus.Downloading
                active.isNotEmpty() -> TaskStatus.Queued
                childTasks.any { it.status == TaskStatus.Paused } -> TaskStatus.Paused
                children.isNotEmpty() && completed == children.size -> TaskStatus.Completed
                childTasks.any { it.status == TaskStatus.Failed } -> TaskStatus.Failed
                else -> TaskStatus.Ready
            }
            val progress = if (children.isEmpty()) 0 else {
                children.sumOf { child -> tasks[child.id]?.progress ?: 0 } / children.size
            }
            result = result + (
                parent.id to DownloadTaskState(
                    status = status,
                    progress = progress,
                    detail = if (children.isNotEmpty()) "$completed/${children.size}" else "",
                    outputUri = childTasks.firstOrNull { it.outputUri.isNotBlank() }?.outputUri.orEmpty(),
                )
                )
        }
        return result
    }

    private fun restoreSession() {
        val session = sessionStore.load() ?: return
        allRows = fillMissingPlaylistThumbnails(session.candidates)
        qualityPool.clear()
        qualityPool.putAll(session.qualityPool.filterValues { it.isNotEmpty() })
        // Backward compatibility with sessions written before qualityPool persistence.
        session.candidates.forEach { candidate ->
            if (qualityPool[candidate.id].isNullOrEmpty()) qualityPool[candidate.id] = listOf(candidate)
        }
        _state.update {
            it.copy(
                // URL input is deliberately ephemeral; restored cards are enough context.
                url = "",
                tasks = session.tasks,
                preferences = session.preferences,
                clipRange = session.clipRange,
                sort = session.sort,
                darkTheme = session.darkTheme,
                selectedIds = session.selectedIds,
                cookieLabel = cookieStore.label(),
                cookieEnabled = cookieStore.activeFile() != null,
            )
        }
        recomputeVisible(session.preferences)
        session.tasks.forEach { (id, task) ->
            val uuid = task.workId.toUuidOrNull()
            when {
                task.status in ACTIVE_TASK_STATUSES && uuid != null -> observeWork(id, uuid)
                task.status in ACTIVE_TASK_STATUSES -> updateTask(id) {
                    task.copy(status = TaskStatus.Paused, detail = "앱 재시작 · 다시 시작 가능")
                }
                else -> Unit
            }
        }
        schedulePersist()
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(250)
            val snapshot = PersistedSession(
                candidates = allRows,
                qualityPool = qualityPool.mapValues { (_, values) -> values.toList() },
                tasks = state.value.tasks,
                preferences = state.value.preferences,
                clipRange = state.value.clipRange,
                sort = state.value.sort,
                darkTheme = state.value.darkTheme,
                selectedIds = state.value.selectedIds,
            )
            withContext(Dispatchers.IO) {
                sessionStore.save(snapshot)
            }
        }
    }

    private fun checkForUpdates() {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { updateChecker.check() }.getOrNull() } ?: return@launch
            _state.update {
                it.copy(updateMessage = info.message, updateUrl = info.apkUrl)
            }
        }
    }

    private fun updateTask(candidateId: String, transform: (DownloadTaskState) -> DownloadTaskState) {
        _state.update { current ->
            val previous = current.tasks[candidateId] ?: DownloadTaskState()
            val updated = current.tasks + (candidateId to transform(previous))
            current.copy(tasks = withPlaylistTaskAggregates(allRows, updated))
        }
    }

    private fun nextOrder(): Long {
        createdCounter += 1
        return createdCounter
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(20)

    private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

    companion object {
        private const val DOWNLOAD_TAG = "clipflow-download"
        private const val LOG_TAG = "ClipFlowVM"
        private val ACTIVE_TASK_STATUSES = setOf(TaskStatus.Queued, TaskStatus.Downloading, TaskStatus.Finishing)
    }
}
