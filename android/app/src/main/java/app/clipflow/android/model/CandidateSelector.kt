package app.clipflow.android.model

fun visibleCandidates(
    candidates: List<MediaCandidate>,
    preferences: DownloadPreferences,
): List<MediaCandidate> {
    val filtered = candidates.filter { candidate ->
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
    val source = filtered.ifEmpty { candidates }
    return source
        .groupBy { listOf(it.height, it.fps, it.dynamicRange, it.extension.uppercase()) }
        .values
        .map { group ->
            group.maxWithOrNull(
                compareBy<MediaCandidate> { it.hasAudio }
                    .thenBy { it.sizeBytes > 0 }
                    .thenBy { it.sizeBytes },
            ) ?: group.first()
        }
        .sortedWith(
            compareByDescending<MediaCandidate> { it.height }
                .thenByDescending { it.fps }
                .thenByDescending { it.sizeBytes },
        )
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
