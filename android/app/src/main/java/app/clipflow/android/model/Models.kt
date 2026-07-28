package app.clipflow.android.model

data class DownloadPreferences(
    val quality: String = "자동",
    val format: String = "MP4",
    val codec: String = "자동",
    val hdrEnabled: Boolean = false,
    /** yt-dlp concurrent-fragments (desktop default 16 for HLS). */
    val concurrency: Int = 16,
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
    val hasAudio: Boolean get() = audioCodec.isNotBlank() && audioCodec != "none" &&
        !audioCodec.equals("null", true)
    val hasVideo: Boolean get() = height > 0 &&
        videoCodec.isNotBlank() &&
        !videoCodec.equals("none", true) &&
        !videoCodec.equals("null", true)
    val isHdr: Boolean get() = dynamicRange.contains("HDR", ignoreCase = true) ||
        dynamicRange.contains("HLG", ignoreCase = true) ||
        dynamicRange.contains("PQ", ignoreCase = true)

    /**
     * yt-dlp -f selector. YouTube adaptive streams are video-only or audio-only;
     * never download a bare audio itag as the "video" card.
     */
    val formatSelector: String
        get() = when {
            formatId in setOf("direct", "best", "playlist", "loading", "failed") ->
                "bestvideo*+bestaudio/best/best"
            formatId.startsWith("chzzk") || formatId.startsWith("browser") ||
                formatId.startsWith("soop") || formatId.startsWith("cime") ||
                formatId.startsWith("anilife") -> "best"
            // Site is YouTube (or generic ytdlp): merge video+audio when needed.
            isYoutubeSource || route == "ytdlp" -> when {
                !hasVideo -> "bestvideo*+bestaudio/best/best"
                !hasAudio -> "$formatId+bestaudio[ext=m4a]/$formatId+bestaudio/best"
                else -> formatId // progressive already has both
            }
            hasAudio && hasVideo -> formatId
            hasVideo && !hasAudio -> "$formatId+bestaudio[ext=m4a]/$formatId+bestaudio/$formatId"
            else -> "bestvideo*+bestaudio/best/best"
        }

    val isYoutubeSource: Boolean
        get() = sourceUrl.contains("youtube.com", ignoreCase = true) ||
            sourceUrl.contains("youtu.be", ignoreCase = true)

    /** Direct progressive only for known direct routes — never googlevideo signed URLs. */
    val prefersDirectUrl: Boolean
        get() = mediaUrl.isNotBlank() && !isManifest && !isYoutubeSource && (
            formatId == "direct" ||
                route in setOf("chzzk", "browser", "direct", "anilife")
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
    /** Private work-dir key under files/downloads/ — used to wipe partials on delete. */
    val taskKey: String = "",
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
