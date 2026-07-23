package app.clipflow.android.data

import android.content.Context
import app.clipflow.android.model.ClipRange
import app.clipflow.android.model.DownloadPreferences
import app.clipflow.android.model.DownloadTaskState
import app.clipflow.android.model.MediaCandidate
import app.clipflow.android.model.RowKind
import app.clipflow.android.model.SortKey
import app.clipflow.android.model.SortState
import app.clipflow.android.model.TaskStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PersistedSession(
    val candidates: List<MediaCandidate>,
    val tasks: Map<String, DownloadTaskState>,
    val preferences: DownloadPreferences,
    val clipRange: ClipRange,
    val sort: SortState,
    val darkTheme: Boolean,
    val selectedIds: Set<String>,
    val url: String,
)

class SessionStore(context: Context) {
    private val file = File(context.filesDir, "session/ui-state.json")

    fun load(): PersistedSession? {
        if (!file.isFile) return null
        return runCatching {
            val root = JSONObject(file.readText())
            PersistedSession(
                candidates = root.optJSONArray("candidates")?.toCandidates().orEmpty(),
                tasks = root.optJSONObject("tasks")?.toTasks().orEmpty(),
                preferences = root.optJSONObject("preferences")?.toPreferences() ?: DownloadPreferences(),
                clipRange = root.optJSONObject("clipRange")?.toClipRange() ?: ClipRange(),
                sort = root.optJSONObject("sort")?.toSort() ?: SortState(),
                darkTheme = root.optBoolean("darkTheme", false),
                selectedIds = root.optJSONArray("selectedIds")?.toStringSet().orEmpty(),
                url = root.optString("url"),
            )
        }.getOrNull()
    }

    fun save(session: PersistedSession) {
        file.parentFile?.mkdirs()
        val root = JSONObject()
            .put("url", session.url)
            .put("darkTheme", session.darkTheme)
            .put("selectedIds", JSONArray(session.selectedIds.toList()))
            .put("preferences", session.preferences.toJson())
            .put("clipRange", session.clipRange.toJson())
            .put("sort", session.sort.toJson())
            .put("candidates", JSONArray(session.candidates.map { it.toJson() }))
            .put(
                "tasks",
                JSONObject().also { obj ->
                    session.tasks.forEach { (id, task) -> obj.put(id, task.toJson()) }
                },
            )
        file.writeText(root.toString())
    }

    fun clear() {
        file.delete()
    }

    private fun JSONArray.toStringSet(): Set<String> = buildSet {
        for (i in 0 until length()) add(optString(i))
    }

    private fun JSONArray.toCandidates(): List<MediaCandidate> = buildList {
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: continue
            add(item.toCandidate())
        }
    }

    private fun JSONObject.toTasks(): Map<String, DownloadTaskState> {
        val map = mutableMapOf<String, DownloadTaskState>()
        keys().forEach { key ->
            val item = optJSONObject(key) ?: return@forEach
            map[key] = DownloadTaskState(
                workId = item.optString("workId"),
                status = runCatching { TaskStatus.valueOf(item.optString("status", TaskStatus.Ready.name)) }
                    .getOrDefault(TaskStatus.Ready),
                progress = item.optInt("progress"),
                detail = item.optString("detail"),
                outputName = item.optString("outputName"),
                outputUri = item.optString("outputUri"),
            )
        }
        return map
    }

    private fun JSONObject.toPreferences() = DownloadPreferences(
        quality = optString("quality", "자동"),
        format = optString("format", "MP4"),
        codec = optString("codec", "자동"),
        hdrEnabled = optBoolean("hdrEnabled", false),
        concurrency = optInt("concurrency", 3).coerceIn(1, 8),
    )

    private fun JSONObject.toClipRange() = ClipRange(
        startSeconds = optInt("startSeconds", -1).takeIf { it >= 0 },
        endSeconds = optInt("endSeconds", -1).takeIf { it >= 0 },
        exact = optBoolean("exact", false),
    )

    private fun JSONObject.toSort() = SortState(
        key = runCatching { SortKey.valueOf(optString("key", SortKey.Latest.name)) }
            .getOrDefault(SortKey.Latest),
        descending = optBoolean("descending", true),
    )

    private fun JSONObject.toCandidate() = MediaCandidate(
        id = optString("id"),
        sourceUrl = optString("sourceUrl"),
        mediaUrl = optString("mediaUrl"),
        title = optString("title"),
        uploader = optString("uploader"),
        thumbnailUrl = optString("thumbnailUrl"),
        formatId = optString("formatId"),
        extension = optString("extension", "mp4"),
        width = optInt("width"),
        height = optInt("height"),
        fps = optInt("fps"),
        videoCodec = optString("videoCodec"),
        audioCodec = optString("audioCodec"),
        dynamicRange = optString("dynamicRange"),
        sizeBytes = optLong("sizeBytes"),
        durationSeconds = optInt("durationSeconds"),
        isManifest = optBoolean("isManifest"),
        kind = runCatching { RowKind.valueOf(optString("kind", RowKind.Video.name)) }
            .getOrDefault(RowKind.Video),
        parentId = optString("parentId"),
        playlistIndex = optInt("playlistIndex"),
        itemCount = optInt("itemCount"),
        expanded = optBoolean("expanded"),
        createdOrder = optLong("createdOrder", System.currentTimeMillis()),
        childLoading = optBoolean("childLoading"),
        route = optString("route", "ytdlp"),
    )

    private fun DownloadPreferences.toJson() = JSONObject()
        .put("quality", quality)
        .put("format", format)
        .put("codec", codec)
        .put("hdrEnabled", hdrEnabled)
        .put("concurrency", concurrency)

    private fun ClipRange.toJson() = JSONObject()
        .put("startSeconds", startSeconds ?: -1)
        .put("endSeconds", endSeconds ?: -1)
        .put("exact", exact)

    private fun SortState.toJson() = JSONObject()
        .put("key", key.name)
        .put("descending", descending)

    private fun DownloadTaskState.toJson() = JSONObject()
        .put("workId", workId)
        .put("status", status.name)
        .put("progress", progress)
        .put("detail", detail)
        .put("outputName", outputName)
        .put("outputUri", outputUri)

    private fun MediaCandidate.toJson() = JSONObject()
        .put("id", id)
        .put("sourceUrl", sourceUrl)
        .put("mediaUrl", mediaUrl)
        .put("title", title)
        .put("uploader", uploader)
        .put("thumbnailUrl", thumbnailUrl)
        .put("formatId", formatId)
        .put("extension", extension)
        .put("width", width)
        .put("height", height)
        .put("fps", fps)
        .put("videoCodec", videoCodec)
        .put("audioCodec", audioCodec)
        .put("dynamicRange", dynamicRange)
        .put("sizeBytes", sizeBytes)
        .put("durationSeconds", durationSeconds)
        .put("isManifest", isManifest)
        .put("kind", kind.name)
        .put("parentId", parentId)
        .put("playlistIndex", playlistIndex)
        .put("itemCount", itemCount)
        .put("expanded", expanded)
        .put("createdOrder", createdOrder)
        .put("childLoading", childLoading)
        .put("route", route)
}
