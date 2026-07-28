package app.clipflow.android.model

fun visibleCandidates(
    candidates: List<MediaCandidate>,
    preferences: DownloadPreferences,
): List<MediaCandidate> {
    val filtered = candidates.filter { candidate ->
        if (candidate.kind == RowKind.Playlist || candidate.childLoading) return@filter true
        val qualityMatches = preferences.quality == "자동" ||
            preferences.quality.removeSuffix("p").toIntOrNull() == candidate.height
        val codecMatches = preferences.codec == "자동" || when (preferences.codec) {
            "H264" -> candidate.videoCodec.contains("avc", true) || candidate.videoCodec.contains("h264", true)
            "H265" -> candidate.videoCodec.contains("hevc", true) || candidate.videoCodec.contains("h265", true)
            "AV1" -> candidate.videoCodec.contains("av01", true) || candidate.videoCodec.contains("av1", true)
            "VP9" -> candidate.videoCodec.contains("vp9", true) || candidate.videoCodec.contains("vp09", true)
            else -> true
        }
        qualityMatches && codecMatches && (preferences.hdrEnabled || !candidate.isHdr)
    }
    // Drop pure audio rows so 0.7MB itag-139 never wins the card.
    val videoOnly = filtered.filter {
        it.kind == RowKind.Playlist || it.childLoading || it.hasVideo || it.formatId == "best"
    }
    val source = videoOnly.ifEmpty { filtered.ifEmpty { candidates } }
    return source
        .groupBy {
            if (it.kind != RowKind.Video && it.kind != RowKind.PlaylistChild) {
                listOf(it.id)
            } else {
                listOf(it.sourceUrl, it.height, it.fps, it.dynamicRange, it.extension.uppercase())
            }
        }
        .values
        .map { group ->
            if (group.first().kind == RowKind.Playlist || group.first().childLoading) {
                group.first()
            } else {
                group.maxWithOrNull(
                    compareBy<MediaCandidate> { it.hasVideo }
                        .thenBy { it.hasAudio }
                        .thenBy { it.sizeBytes > 0 }
                        .thenBy { it.sizeBytes },
                ) ?: group.first()
            }
        }
        .sortedWith(
            compareByDescending<MediaCandidate> { it.height }
                .thenByDescending { it.fps }
                .thenByDescending { it.sizeBytes },
        )
}

/** Collapse multi-quality analysis into one preferred row per source URL. */
fun preferredVisibleCandidate(
    qualities: List<MediaCandidate>,
    preferences: DownloadPreferences,
): MediaCandidate? = visibleCandidates(qualities, preferences).firstOrNull()

fun sortCandidates(candidates: List<MediaCandidate>, sort: SortState): List<MediaCandidate> {
    val top = candidates.filter { it.kind != RowKind.PlaylistChild }
    val childrenByParent = candidates.filter { it.kind == RowKind.PlaylistChild }
        .groupBy { it.parentId }

    val sortedTop = when (sort.key) {
        SortKey.Name -> {
            val comparator = compareBy<MediaCandidate> { it.title.lowercase() }
            if (sort.descending) top.sortedWith(comparator.reversed()) else top.sortedWith(comparator)
        }
        SortKey.Latest -> {
            val comparator = compareBy<MediaCandidate> { it.createdOrder }
            if (sort.descending) top.sortedWith(comparator.reversed()) else top.sortedWith(comparator)
        }
    }

    return buildList {
        for (row in sortedTop) {
            add(row)
            if (row.kind == RowKind.Playlist && row.expanded) {
                val children = childrenByParent[row.id].orEmpty()
                    .sortedWith(
                        compareBy<MediaCandidate> { it.playlistIndex }
                            .thenBy { it.createdOrder },
                    )
                addAll(children)
            }
        }
    }
}

fun extractUrls(text: String): List<String> {
    val found = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .map { it.value.trim().trimEnd(',', '.', ')', ']', '>', '"', '\'') }
        .filter { it.startsWith("http://") || it.startsWith("https://") }
        .distinct()
        .toList()
    return found
}

/** Google favicon service — same approach as desktop ClipFlow rows. */
fun faviconUrlFor(pageUrl: String, size: Int = 64): String {
    val host = runCatching {
        java.net.URI(pageUrl).host?.lowercase().orEmpty()
    }.getOrDefault("")
    if (host.isBlank()) return ""
    val origin = "https://$host"
    val query = java.net.URLEncoder.encode(origin, Charsets.UTF_8.name())
    return "https://t0.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=$query&size=$size"
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "--"
    val mb = bytes.toDouble() / 1_000_000.0
    return if (mb >= 1_000) "%.1f GB".format(mb / 1_000.0) else "%.1f MB".format(mb)
}

fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "--:--"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remain = seconds % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, remain)
    else "%02d:%02d".format(minutes, remain)
}

fun parseTimecode(value: String): Int? {
    val text = value.trim()
    if (text.isBlank()) return null
    val parts = text.split(":")
    if (parts.size !in 1..3 || parts.any { it.toIntOrNull() == null }) return null
    val numbers = parts.map(String::toInt)
    return when (numbers.size) {
        1 -> numbers[0]
        2 -> numbers[0] * 60 + numbers[1]
        else -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
    }.takeIf { it >= 0 }
}
