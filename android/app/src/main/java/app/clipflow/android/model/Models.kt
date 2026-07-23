package app.clipflow.android.model

data class DownloadPreferences(
    val quality: String = "자동",
    val format: String = "MP4",
    val codec: String = "자동",
    val hdrEnabled: Boolean = false,
    val concurrency: Int = 3,
)

data class ClipRange(
    val startSeconds: Int? = null,
    val endSeconds: Int? = null,
    val exact: Boolean = false,
) {
    val isSet: Boolean get() = startSeconds != null || endSeconds != null
}

enum class RowKind {
    Video,
    Playlist,
    PlaylistChild,
}

enum class SortKey {
    Latest,
    Name,
}

data class SortState(
    val key: SortKey = SortKey.Latest,
    val descending: Boolean = true,
)

data class MediaCandidate(
    val id: String,
    val sourceUrl: String,
    val mediaUrl: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val formatId: String,
    val extension: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoCodec: String,
    val audioCodec: String,
    val dynamicRange: String,
    val sizeBytes: Long,
    val durationSeconds: Int,
    val isManifest: Boolean,
    val kind: RowKind = RowKind.Video,
    val parentId: String = "",
    val playlistIndex: Int = 0,
    val itemCount: Int = 0,
    val expanded: Boolean = false,
    val createdOrder: Long = System.currentTimeMillis(),
    val childLoading: Boolean = false,
    val route: String = "ytdlp",
    val qualities: List<MediaCandidate> = emptyList(),
) {
    val hasAudio: Boolean get() = audioCodec.isNotBlank() && audioCodec != "none"
    val isHdr: Boolean get() = dynamicRange.contains("HDR", ignoreCase = true) ||
        dynamicRange.contains("HLG", ignoreCase = true) ||
        dynamicRange.contains("PQ", ignoreCase = true)

    val formatSelector: String
        get() = when {
            formatId in setOf("direct", "best", "playlist", "loading", "failed") -> "best"
            formatId.startsWith("chzzk") || formatId.startsWith("browser") ||
                formatId.startsWith("soop") || formatId.startsWith("cime") -> "best"
            hasAudio -> formatId
            else -> "$formatId+bestaudio[ext=m4a]/$formatId+bestaudio/$formatId"
        }

    val prefersDirectUrl: Boolean
        get() = mediaUrl.isNotBlank() && !isManifest && (
            formatId == "direct" ||
                route in setOf("chzzk", "browser", "direct") ||
                mediaUrl.contains(".mp4", ignoreCase = true)
            )
}

enum class TaskStatus {
    Ready,
    Queued,
    Downloading,
    Finishing,
    Paused,
    Completed,
    Failed,
}

data class DownloadTaskState(
    val workId: String = "",
    val status: TaskStatus = TaskStatus.Ready,
    val progress: Int = 0,
    val detail: String = "",
    val outputName: String = "",
    val outputUri: String = "",
)

data class ClipFlowUiState(
    val url: String = "",
    val analyzing: Boolean = false,
    val analysisMessage: String = "",
    val analysisQueueRemaining: Int = 0,
    val candidates: List<MediaCandidate> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val tasks: Map<String, DownloadTaskState> = emptyMap(),
    val preferences: DownloadPreferences = DownloadPreferences(),
    val clipRange: ClipRange = ClipRange(),
    val sort: SortState = SortState(),
    val darkTheme: Boolean = false,
    val outputTreeUri: String = "",
    val outputLabel: String = "다운로드/ClipFlow",
    val cookieLabel: String = "쿠키 미사용",
    val cookieEnabled: Boolean = false,
    val updateMessage: String = "",
    val updateUrl: String = "",
    val error: String = "",
)
