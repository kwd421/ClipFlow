package app.clipflow.android

import android.app.Application
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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
import app.clipflow.android.model.preferredVisibleCandidate
import app.clipflow.android.model.sortCandidates
import app.clipflow.android.model.visibleCandidates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID

class ClipFlowViewModel(application: Application) : AndroidViewModel(application) {
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

    /** All analyzed rows including hidden playlist children and multi-quality pools. */
    private var allRows: List<MediaCandidate> = emptyList()
    private val qualityPool = mutableMapOf<String, List<MediaCandidate>>()
    private val observedWorks = mutableSetOf<UUID>()
    private val analysisQueue = ArrayDeque<String>()
    private var analysisJob: Job? = null
    private var persistJob: Job? = null
    private var createdCounter = System.currentTimeMillis()

    init {
        restoreSession()
        checkForUpdates()
    }

    fun setUrl(value: String) {
        _state.update { it.copy(url = value, error = "") }
        schedulePersist()
    }

    fun analyze() {
        val urls = extractUrls(state.value.url)
        if (urls.isEmpty()) {
            _state.update { it.copy(error = "http 또는 https URL을 입력하세요. 여러 개는 줄바꿈으로 넣을 수 있습니다.") }
            return
        }
        analysisQueue.clear()
        analysisQueue.addAll(urls)
        _state.update {
            it.copy(
                analyzing = true,
                analysisMessage = if (urls.size > 1) "대기열 ${urls.size}개 분석 중" else "영상 정보를 확인하는 중",
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

    fun toggleSelectAll() {
        _state.update { current ->
            val all = current.candidates
                .filter { it.kind != RowKind.Playlist }
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
            .forEach(::enqueueDownload)
    }

    fun downloadPlaylist(playlistId: String) {
        allRows.filter { it.parentId == playlistId && it.kind == RowKind.PlaylistChild && !it.childLoading }
            .forEach(::enqueueDownload)
    }

    fun download(candidate: MediaCandidate) = enqueueDownload(candidate)

    fun downloadSegment(candidate: MediaCandidate, clipRange: ClipRange) = enqueueDownload(candidate, clipRange)

    fun extractAudio(candidate: MediaCandidate, format: String) {
        val normalizedFormat = format.lowercase().takeIf { it in setOf("wav", "mp3") } ?: return
        val current = state.value
        val taskKey = hash("${candidate.sourceUrl}|audio|$normalizedFormat")
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_URL to candidate.sourceUrl,
                    DownloadWorker.KEY_FORMAT to "bestaudio/best",
                    DownloadWorker.KEY_TREE_URI to current.outputTreeUri,
                    DownloadWorker.KEY_CONCURRENCY to current.preferences.concurrency,
                    DownloadWorker.KEY_START to -1,
                    DownloadWorker.KEY_END to -1,
                    DownloadWorker.KEY_EXACT_CUT to false,
                    DownloadWorker.KEY_AUDIO_FORMAT to normalizedFormat,
                    DownloadWorker.KEY_TASK_KEY to taskKey,
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
        updateTask(candidateId) { it.copy(status = TaskStatus.Paused, detail = "일시정지됨") }
        schedulePersist()
    }

    fun resume(candidate: MediaCandidate) = enqueueDownload(candidate)

    fun remove(candidateId: String) {
        state.value.tasks[candidateId]?.workId?.toUuidOrNull()?.let(workManager::cancelWorkById)
        val row = allRows.firstOrNull { it.id == candidateId }
        allRows = if (row?.kind == RowKind.Playlist) {
            allRows.filterNot { it.id == candidateId || it.parentId == candidateId }
        } else {
            allRows.filterNot { it.id == candidateId }
        }
        qualityPool.remove(candidateId)
        qualityPool.keys.filter { key -> qualityPool[key].orEmpty().none { it.id in allRows.map(MediaCandidate::id) } }
        _state.update { current ->
            current.copy(
                selectedIds = current.selectedIds - candidateId,
                tasks = current.tasks - candidateId,
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
        _state.update { it.copy(cookieLabel = "쿠키 미사용", cookieEnabled = false) }
        schedulePersist()
    }

    fun clearError() {
        _state.update { it.copy(error = "") }
    }

    fun dismissUpdate() {
        _state.update { it.copy(updateMessage = "", updateUrl = "") }
    }

    fun playOutput(candidateId: String) {
        val uri = state.value.tasks[candidateId]?.outputUri?.takeIf(String::isNotBlank) ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri.toUri(), "video/mp4")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { getApplication<Application>().startActivity(intent) }
            .onFailure { _state.update { it.copy(error = "재생할 앱을 찾을 수 없습니다.") } }
    }

    fun openOutputFolder(candidateId: String) {
        val task = state.value.tasks[candidateId] ?: return
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

    fun deleteOutput(candidateId: String) {
        val task = state.value.tasks[candidateId] ?: return
        val uri = task.outputUri.takeIf(String::isNotBlank)?.toUri()
        if (uri != null) {
            runCatching { getApplication<Application>().contentResolver.delete(uri, null, null) }
        }
        _state.update { current -> current.copy(tasks = current.tasks - candidateId) }
        schedulePersist()
    }

    private suspend fun drainAnalysisQueue() {
        while (analysisQueue.isNotEmpty()) {
            val url = analysisQueue.removeFirst()
            val remaining = analysisQueue.size
            _state.update {
                it.copy(
                    analyzing = true,
                    analysisMessage = if (remaining > 0) "분석 중 · 남은 ${remaining + 1}개" else "영상 정보를 확인하는 중",
                    analysisQueueRemaining = remaining + 1,
                )
            }
            runCatching { withContext(Dispatchers.IO) { analyzeOne(url) } }
                .onFailure { error ->
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

    private fun analyzeOne(url: String) {
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
                    val analyzed = analyzer.analyze(entry.url, allowBrowserFallback = true)
                    val preferred = preferredVisibleCandidate(analyzed.candidates, state.value.preferences)
                        ?: analyzed.candidates.first()
                    val child = preferred.copy(
                        id = "${parent.id}-child-${entry.index}-${preferred.formatId}",
                        title = preferred.title.ifBlank { entry.title },
                        kind = RowKind.PlaylistChild,
                        parentId = parent.id,
                        playlistIndex = entry.index,
                        createdOrder = loading.createdOrder,
                        childLoading = false,
                        route = analyzed.route,
                    )
                    qualityPool[child.id] = analyzed.candidates.map {
                        it.copy(parentId = parent.id, kind = RowKind.PlaylistChild, playlistIndex = entry.index)
                    }
                    allRows = allRows.map { if (it.id == loadingId) child else it }
                    recomputeVisible()
                }.onFailure {
                    allRows = allRows.map {
                        if (it.id == loadingId) {
                            it.copy(
                                childLoading = false,
                                title = "${entry.title} (실패)",
                                formatId = "failed",
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
            return
        }

        val result = analyzer.analyze(url)
        val preferred = preferredVisibleCandidate(result.candidates, state.value.preferences)
            ?: result.candidates.first()
        val row = preferred.copy(
            id = preferred.id.ifBlank { hash(url) },
            createdOrder = nextOrder(),
            route = result.route,
        )
        qualityPool[row.id] = result.candidates
        // Replace same source URL single row, otherwise prepend.
        allRows = listOf(row) + allRows.filterNot {
            it.kind != RowKind.PlaylistChild && it.sourceUrl == url
        }
        recomputeVisible(selectId = row.id)
    }

    private fun enqueueDownload(candidate: MediaCandidate, clipRange: ClipRange = state.value.clipRange) {
        if (candidate.kind == RowKind.Playlist || candidate.childLoading) return
        val current = state.value
        val taskKey = hash("${candidate.sourceUrl}|${candidate.formatSelector}|${candidate.mediaUrl}|$clipRange")
        val useDirect = candidate.prefersDirectUrl || candidate.route in setOf("chzzk", "browser", "direct")
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_URL to candidate.sourceUrl,
                    DownloadWorker.KEY_DIRECT_URL to candidate.mediaUrl.takeIf { useDirect || candidate.formatId == "direct" }.orEmpty(),
                    DownloadWorker.KEY_PREFER_DIRECT to useDirect,
                    DownloadWorker.KEY_FORMAT to candidate.formatSelector,
                    DownloadWorker.KEY_TREE_URI to current.outputTreeUri,
                    DownloadWorker.KEY_CONCURRENCY to current.preferences.concurrency,
                    DownloadWorker.KEY_START to (clipRange.startSeconds ?: -1),
                    DownloadWorker.KEY_END to (clipRange.endSeconds ?: -1),
                    DownloadWorker.KEY_EXACT_CUT to clipRange.exact,
                    DownloadWorker.KEY_TASK_KEY to taskKey,
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
            )
        }
        observeWork(candidate.id, request.id)
        schedulePersist()
    }

    private fun observeWork(candidateId: String, workId: UUID) {
        if (!observedWorks.add(workId)) return
        viewModelScope.launch {
            while (isActive) {
                val info = withContext(Dispatchers.IO) { workManager.getWorkInfoById(workId).get() }
                if (info == null) {
                    delay(100)
                    continue
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
                        outputName = info.outputData.getString(DownloadWorker.OUTPUT_NAME).orEmpty(),
                        outputUri = info.outputData.getString(DownloadWorker.OUTPUT_URI).orEmpty()
                            .ifBlank { old.outputUri },
                    )
                }
                if (info.state.isFinished) {
                    schedulePersist()
                    break
                }
                delay(300)
            }
        }
    }

    private fun observeAuxiliaryWork(workId: UUID) {
        viewModelScope.launch {
            while (isActive) {
                val info = withContext(Dispatchers.IO) { workManager.getWorkInfoById(workId).get() }
                if (info == null || !info.state.isFinished) {
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
                ) ?: row
            }
        }
        val sorted = sortCandidates(displayRows, state.value.sort)
        _state.update { current ->
            val selected = when {
                // Auto-select only the newly analyzed card; never accumulate the whole list.
                selectId != null -> setOf(selectId)
                else -> current.selectedIds.intersect(sorted.mapTo(mutableSetOf()) { it.id })
            }
            current.copy(
                preferences = preferences,
                candidates = sorted,
                selectedIds = selected,
            )
        }
    }

    private fun restoreSession() {
        val session = sessionStore.load() ?: return
        allRows = session.candidates
        session.candidates.forEach { qualityPool[it.id] = listOf(it) }
        _state.update {
            it.copy(
                url = session.url,
                tasks = session.tasks.mapValues { (_, task) ->
                    // Active works may no longer exist after process death.
                    if (task.status in setOf(TaskStatus.Queued, TaskStatus.Downloading, TaskStatus.Finishing)) {
                        task.copy(status = TaskStatus.Paused, detail = "앱 재시작 · 다시 시작 가능")
                    } else task
                },
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
        // Re-attach observers for unfinished works if IDs still valid.
        session.tasks.forEach { (id, task) ->
            task.workId.toUuidOrNull()?.let { uuid ->
                if (task.status in setOf(TaskStatus.Queued, TaskStatus.Downloading, TaskStatus.Finishing, TaskStatus.Paused)) {
                    // Leave paused; user can resume.
                } else if (task.status == TaskStatus.Completed) {
                    // no-op
                } else {
                    observeWork(id, uuid)
                }
            }
        }
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(250)
            sessionStore.save(
                PersistedSession(
                    candidates = allRows,
                    tasks = state.value.tasks,
                    preferences = state.value.preferences,
                    clipRange = state.value.clipRange,
                    sort = state.value.sort,
                    darkTheme = state.value.darkTheme,
                    selectedIds = state.value.selectedIds,
                    url = state.value.url,
                ),
            )
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
            current.copy(tasks = current.tasks + (candidateId to transform(previous)))
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
    }
}
