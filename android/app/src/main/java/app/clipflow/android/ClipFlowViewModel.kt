package app.clipflow.android

import android.app.Application
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.clipflow.android.data.DownloadWorker
import app.clipflow.android.data.CookieFileStore
import app.clipflow.android.data.YoutubeDlAnalyzer
import app.clipflow.android.model.ClipFlowUiState
import app.clipflow.android.model.ClipRange
import app.clipflow.android.model.DownloadPreferences
import app.clipflow.android.model.DownloadTaskState
import app.clipflow.android.model.MediaCandidate
import app.clipflow.android.model.TaskStatus
import app.clipflow.android.model.visibleCandidates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

class ClipFlowViewModel(application: Application) : AndroidViewModel(application) {
    private val analyzer = YoutubeDlAnalyzer(application)
    private val cookieStore = CookieFileStore(application)
    private val workManager = WorkManager.getInstance(application)
    private val preferencesStore = application.getSharedPreferences("clipflow", 0)
    private val _state = MutableStateFlow(
        ClipFlowUiState(
            outputTreeUri = preferencesStore.getString("output_tree_uri", "").orEmpty(),
            outputLabel = preferencesStore.getString("output_label", "다운로드/ClipFlow")
                ?: "다운로드/ClipFlow",
            cookieLabel = cookieStore.label(),
            cookieEnabled = cookieStore.activeFile() != null,
        ),
    )
    val state: StateFlow<ClipFlowUiState> = _state.asStateFlow()
    private var allCandidates: List<MediaCandidate> = emptyList()
    private val observedWorks = mutableSetOf<UUID>()

    fun setUrl(value: String) {
        _state.update { it.copy(url = value, error = "") }
    }

    fun analyze() {
        val url = state.value.url.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _state.update { it.copy(error = "http 또는 https로 시작하는 URL을 입력하세요.") }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    analyzing = true,
                    analysisMessage = "영상 정보를 확인하는 중",
                    candidates = emptyList(),
                    selectedIds = emptySet(),
                    error = "",
                )
            }
            runCatching { withContext(Dispatchers.IO) { analyzer.analyze(url) } }
                .onSuccess { result ->
                    allCandidates = result.candidates
                    val visible = visibleCandidates(allCandidates, state.value.preferences)
                    _state.update {
                        it.copy(
                            analyzing = false,
                            analysisMessage = "",
                            candidates = visible,
                            selectedIds = visible.firstOrNull()?.let { candidate -> setOf(candidate.id) }.orEmpty(),
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            analyzing = false,
                            analysisMessage = "",
                            error = error.message ?: "URL 분석에 실패했습니다.",
                        )
                    }
                }
        }
    }

    fun updatePreferences(value: DownloadPreferences) {
        val visible = visibleCandidates(allCandidates, value)
        _state.update { current ->
            current.copy(
                preferences = value,
                candidates = visible,
                selectedIds = current.selectedIds.intersect(visible.mapTo(mutableSetOf()) { it.id })
                    .ifEmpty { visible.firstOrNull()?.let { setOf(it.id) }.orEmpty() },
            )
        }
    }

    fun updateClipRange(value: ClipRange) {
        _state.update { it.copy(clipRange = value) }
    }

    fun toggleSelected(candidateId: String) {
        _state.update { current ->
            val selected = current.selectedIds.toMutableSet()
            if (!selected.add(candidateId)) selected.remove(candidateId)
            current.copy(selectedIds = selected)
        }
    }

    fun toggleSelectAll() {
        _state.update { current ->
            val all = current.candidates.mapTo(mutableSetOf()) { it.id }
            current.copy(selectedIds = if (current.selectedIds.size == all.size) emptySet() else all)
        }
    }

    fun downloadSelected() {
        val current = state.value
        current.candidates.filter { it.id in current.selectedIds }.forEach(::enqueueDownload)
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
    }

    fun resume(candidate: MediaCandidate) = enqueueDownload(candidate)

    fun remove(candidateId: String) {
        state.value.tasks[candidateId]?.workId?.toUuidOrNull()?.let(workManager::cancelWorkById)
        allCandidates = allCandidates.filterNot { it.id == candidateId }
        _state.update { current ->
            current.copy(
                candidates = current.candidates.filterNot { it.id == candidateId },
                selectedIds = current.selectedIds - candidateId,
                tasks = current.tasks - candidateId,
            )
        }
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
    }

    fun useDefaultOutput() {
        preferencesStore.edit {
            remove("output_tree_uri")
            putString("output_label", "다운로드/ClipFlow")
        }
        _state.update { it.copy(outputTreeUri = "", outputLabel = "다운로드/ClipFlow") }
    }

    fun importCookieFile(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { cookieStore.import(uri) } }
                .onSuccess { label ->
                    _state.update { it.copy(cookieLabel = label, cookieEnabled = true, error = "") }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "쿠키 파일을 가져오지 못했습니다.") }
                }
        }
    }

    fun clearCookieFile() {
        cookieStore.clear()
        _state.update { it.copy(cookieLabel = "쿠키 미사용", cookieEnabled = false) }
    }

    fun clearError() {
        _state.update { it.copy(error = "") }
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
    }

    private fun enqueueDownload(candidate: MediaCandidate, clipRange: ClipRange = state.value.clipRange) {
        val current = state.value
        val taskKey = hash("${candidate.sourceUrl}|${candidate.formatSelector}|$clipRange")
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_URL to candidate.sourceUrl,
                    DownloadWorker.KEY_DIRECT_URL to candidate.mediaUrl.takeIf { candidate.formatId == "direct" }.orEmpty(),
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
                if (info.state.isFinished) break
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

    private fun updateTask(candidateId: String, transform: (DownloadTaskState) -> DownloadTaskState) {
        _state.update { current ->
            val previous = current.tasks[candidateId] ?: DownloadTaskState()
            current.copy(tasks = current.tasks + (candidateId to transform(previous)))
        }
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
