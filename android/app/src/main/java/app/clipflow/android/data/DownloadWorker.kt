package app.clipflow.android.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.clipflow.android.ClipFlowApplication
import app.clipflow.android.MainActivity
import app.clipflow.android.R
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    private val processId = id.toString()
    private val cookieStore = CookieFileStore(appContext)
    private val progressPercent = AtomicInteger(0)
    private val progressDetail = AtomicReference("다운로드 중")
    private val progressFinishing = AtomicBoolean(false)

    override fun doWork(): Result {
        val sourceUrl = inputData.getString(KEY_URL).orEmpty()
        val directUrl = inputData.getString(KEY_DIRECT_URL).orEmpty()
        val referer = inputData.getString(KEY_REFERER).orEmpty().ifBlank { sourceUrl }
        val formatSelector = inputData.getString(KEY_FORMAT).orEmpty().ifBlank { "best" }
        val treeUri = inputData.getString(KEY_TREE_URI).orEmpty()
        val audioFormat = inputData.getString(KEY_AUDIO_FORMAT).orEmpty().lowercase()
        val concurrency = inputData.getInt(KEY_CONCURRENCY, 16).coerceIn(1, 16)
        if (sourceUrl.isBlank()) return Result.failure(errorData("URL이 비어 있습니다."))

        val preparingText = if (audioFormat.isNotBlank()) "음원 추출 준비 중" else "다운로드 준비 중"
        setForegroundAsync(notificationInfo(0, preparingText)).get()
        val taskKey = inputData.getString(KEY_TASK_KEY).orEmpty().ifBlank { hash("$sourceUrl|$formatSelector") }
        val workDir = File(applicationContext.filesDir, "downloads/$taskKey").apply { mkdirs() }

        return try {
            val preferDirect = inputData.getBoolean(KEY_PREFER_DIRECT, false)
            val isManifest = directUrl.contains(".m3u8", ignoreCase = true) ||
                directUrl.contains(".mpd", ignoreCase = true) ||
                directUrl.contains("/media/hls", ignoreCase = true) ||
                directUrl.contains("/manifest/", ignoreCase = true) ||
                directUrl.contains("gcdn.app", ignoreCase = true)
            val output = if (
                audioFormat.isBlank() &&
                directUrl.isNotBlank() &&
                clipSection() == null &&
                (preferDirect || isManifest)
            ) {
                if (isManifest) {
                    // Browser-captured HLS/DASH: download the media URL itself with page referer.
                    downloadWithYoutubeDl(
                        directUrl,
                        "best",
                        concurrency,
                        workDir,
                        audioFormat,
                        referer = referer,
                    )
                } else {
                    runCatching { downloadDirectMp4(directUrl, workDir, referer = referer) }
                        .getOrElse { directError ->
                            publishProgress(0, "직접 요청 실패 · yt-dlp로 재시도", false)
                            runCatching {
                                downloadWithYoutubeDl(
                                    sourceUrl,
                                    formatSelector,
                                    concurrency,
                                    workDir,
                                    audioFormat,
                                    referer = referer,
                                )
                            }.getOrElse { throw directError }
                        }
                }
            } else {
                downloadWithYoutubeDl(
                    sourceUrl,
                    formatSelector,
                    concurrency,
                    workDir,
                    audioFormat,
                    referer = referer,
                )
            }

            setProgressAsync(workDataOf(PROGRESS to 100, DETAIL to "파일 저장 중", FINISHING to true)).get()
            val (savedName, savedUri) = saveOutput(output, treeUri)
            output.delete()
            Result.success(workDataOf(OUTPUT_NAME to savedName, OUTPUT_URI to savedUri))
        } catch (error: Throwable) {
            if (isStopped) Result.failure(errorData("일시정지됨"))
            else Result.failure(errorData(error.message ?: "다운로드에 실패했습니다."))
        } finally {
            YoutubeDL.getInstance().destroyProcessById(processId)
        }
    }

    override fun onStopped() {
        YoutubeDL.getInstance().destroyProcessById(processId)
        super.onStopped()
    }

    private fun clipSection(): String? {
        val start = inputData.getInt(KEY_START, -1)
        val end = inputData.getInt(KEY_END, -1)
        if (start < 0 && end < 0) return null
        return "*${start.coerceAtLeast(0)}-${if (end >= 0) end else "inf"}"
    }

    private fun downloadWithYoutubeDl(
        sourceUrl: String,
        formatSelector: String,
        concurrency: Int,
        workDir: File,
        audioFormat: String,
        referer: String = "",
    ): File {
        (applicationContext as ClipFlowApplication).ensureEngine()
        val isHls = sourceUrl.contains(".m3u8", ignoreCase = true) ||
            sourceUrl.contains("/manifest/", ignoreCase = true) ||
            sourceUrl.contains("gcdn.app", ignoreCase = true)
        val request = YoutubeDLRequest(sourceUrl).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--continue")
            addOption("--newline")
            addOption("--no-warnings")
            // Progressive: aria2c multi-connection. HLS: native downloader (clean merge)
            // with desktop YTDLP_CONCURRENT_FRAGMENT_DOWNLOADS=16 default.
            if (!isHls) {
                addOption("--downloader", "libaria2c.so")
                addOption("--downloader-args", "aria2c:-x16 -s16 -k1M")
            }
            val fragmentConcurrency = if (isHls) {
                // Prefer user setting when raised; otherwise match desktop 16.
                maxOf(concurrency, HLS_FRAGMENT_CONCURRENCY).coerceIn(4, 16)
            } else {
                concurrency.coerceIn(1, 8)
            }
            addOption("--concurrent-fragments", fragmentConcurrency)
            addOption("--http-chunk-size", "10485760")
            cookieStore.activeFile()?.let { addOption("--cookies", it.absolutePath) }
            addOption("--user-agent", BrowserMediaFallback.DESKTOP_CHROME_UA)
            if (referer.isNotBlank()) {
                addOption("--referer", referer)
                addOption("--add-header", "Referer:$referer")
                runCatching {
                    val origin = java.net.URI(referer).let { "${it.scheme}://${it.host}" }
                    if (origin.isNotBlank()) addOption("--add-header", "Origin:$origin")
                }
            }
            if (audioFormat.isNotBlank()) {
                addOption("--format", "bestaudio/best")
                addOption("--extract-audio")
                addOption("--audio-format", audioFormat)
            } else if (isHls) {
                // Playlist URL is already quality-specific for AniLife; no "-f best".
                addOption("--merge-output-format", "mp4")
            } else {
                addOption("--format", formatSelector)
                addOption("--merge-output-format", "mp4")
                addOption("--recode-video", "mp4")
            }
            addOption("--paths", workDir.absolutePath)
            val preferredName = inputData.getString(KEY_TITLE).orEmpty()
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trim()
                .take(80)
            if (preferredName.isNotBlank()) {
                addOption("--output", "$preferredName.%(ext)s")
            } else {
                addOption("--output", "%(title).120s [%(id)s].%(ext)s")
            }
            addOption("--print", "after_move:__CLIPFLOW_FILE__%(filepath)s")
            clipSection()?.let { section ->
                addOption("--download-sections", section)
                if (inputData.getBoolean(KEY_EXACT_CUT, false)) {
                    addOption("--force-keyframes-at-cuts")
                }
            }
        }
        val expectedBytes = inputData.getLong(KEY_EXPECTED_BYTES, 0L)
        // HLS often never invokes yt-dlp progress with a useful %; poll workDir size instead.
        // Desktop: progress_hook + _progress_text → "N% · speed" (display_size base 1000).
        val stopTicker = AtomicBoolean(false)
        val ticker = startProgressTicker(workDir, expectedBytes, stopTicker)
        try {
            val response = YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, line ->
                val parsed = parseProgress(line, progress, expectedBytes, workDir)
                val percent = maxOf(parsed.percent, progressPercent.get()).coerceIn(0, 100)
                val etaText = if (etaInSeconds > 0) formatEta(etaInSeconds.toLong()) else parsed.eta
                val detail = progressText(
                    percent = percent,
                    speedText = parsed.speed,
                    etaText = etaText,
                    finishing = percent >= 100 || parsed.finishing,
                    fallback = progressDetail.get(),
                )
                publishProgress(percent, detail, percent >= 100 || parsed.finishing)
            }
            val marked = response.out.lineSequence()
                .lastOrNull { it.startsWith(FILE_MARKER) }
                ?.removePrefix(FILE_MARKER)
                ?.trim()
                ?.let(::File)
                ?.takeIf { it.isFile && it.length() > 0 }
            if (marked != null) return marked
            findFinishedMedia(workDir, audioFormat)?.let { return it }
            val errTail = response.out.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .filterNot { line ->
                    line.startsWith("WARNING:", ignoreCase = true) ||
                        line.startsWith("DEPRECATED", ignoreCase = true) ||
                        line.contains("yt-dlp version", ignoreCase = true) ||
                        line.contains("--update", ignoreCase = true)
                }
                .toList()
                .takeLast(4)
                .joinToString(" | ")
            error(
                if (errTail.isNotBlank()) "완성된 MP4를 만들지 못했습니다: $errTail"
                else "완성된 MP4 파일을 찾지 못했습니다.",
            )
        } finally {
            stopTicker.set(true)
            ticker.interrupt()
            runCatching { ticker.join(800) }
        }
    }

    private fun startProgressTicker(
        workDir: File,
        expectedBytes: Long,
        stop: AtomicBoolean,
    ): Thread {
        val lastBytes = AtomicLong(0L)
        val lastAt = AtomicLong(System.currentTimeMillis())
        // Smoothed speed so UI doesn't flicker to empty between fragment bursts.
        val smoothSpeed = AtomicLong(0L)
        return thread(name = "clipflow-progress", isDaemon = true) {
            while (!stop.get() && !isStopped) {
                try {
                    val written = workDirDownloadedBytes(workDir)
                    val now = System.currentTimeMillis()
                    val prevAt = lastAt.getAndSet(now)
                    val prevBytes = lastBytes.getAndSet(written)
                    val elapsedMs = (now - prevAt).coerceAtLeast(1L)
                    val delta = (written - prevBytes).coerceAtLeast(0L)
                    val instantBps = if (delta > 0) delta * 1000L / elapsedMs else 0L
                    val prevSmooth = smoothSpeed.get()
                    val speedBps = when {
                        instantBps > 0L && prevSmooth > 0L -> (prevSmooth * 2L + instantBps) / 3L
                        instantBps > 0L -> instantBps
                        prevSmooth > 0L -> (prevSmooth * 3L) / 4L // decay when idle between fragments
                        else -> 0L
                    }
                    smoothSpeed.set(speedBps)
                    val fromSize = if (expectedBytes > 0 && written > 0) {
                        ((written * 100L) / expectedBytes).toInt().coerceIn(0, 99)
                    } else {
                        0
                    }
                    val percent = maxOf(fromSize, progressPercent.get()).coerceIn(0, 99)
                    val speedText = formatSpeed(speedBps)
                    val detail = progressText(
                        percent = percent,
                        speedText = speedText,
                        etaText = "",
                        finishing = false,
                        fallback = if (written > 0) "다운로드 중" else progressDetail.get(),
                    )
                    // Always push while bytes are moving or detail changed — UI must update.
                    if (written > 0 ||
                        percent > progressPercent.get() ||
                        detail != progressDetail.get() ||
                        progressDetail.get() in setOf("다운로드 중", "대기 중", "다운로드 준비 중")
                    ) {
                        publishProgress(percent, detail, false)
                    }
                    Thread.sleep(400)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {
                    try {
                        Thread.sleep(400)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    private fun workDirDownloadedBytes(workDir: File): Long {
        if (!workDir.exists()) return 0L
        return workDir.walkTopDown()
            .filter { it.isFile }
            .filter {
                val n = it.name.lowercase()
                // Count any growing media/temp artifact yt-dlp leaves in the work dir.
                !n.endsWith(".ytdl") &&
                    !n.endsWith(".json") &&
                    !n.endsWith(".vtt") &&
                    !n.endsWith(".srt") &&
                    it.length() > 0L
            }
            .sumOf { it.length() }
    }

    /**
     * Desktop clipflow_qt._progress_text / engine.display_size:
     * "N% · 12.3 MB/s" (SIZE_UNIT_BASE=1000 on non-Windows).
     */
    private fun progressText(
        percent: Int,
        speedText: String,
        etaText: String,
        finishing: Boolean,
        fallback: String,
    ): String {
        if (finishing || percent >= 100) return "마무리 중"
        val speed = speedText.trim()
        val eta = etaText.trim()
        if (percent > 0 && speed.isNotBlank()) {
            val base = "$percent% · $speed"
            return if (eta.isNotBlank()) "$base · ETA $eta" else base
        }
        if (percent > 0) {
            // Keep prior speed line if callback had no speed this tick.
            val existing = fallback.trim()
            if (existing.contains('%') && existing.contains('·')) {
                val kept = existing.replace(Regex("""^\d+%"""), "$percent%")
                return if (eta.isNotBlank() && !kept.contains("ETA")) "$kept · ETA $eta" else kept
            }
            return if (eta.isNotBlank()) "$percent% · ETA $eta" else "$percent%"
        }
        if (speed.isNotBlank()) return "다운로드 중 · $speed"
        return fallback.ifBlank { "다운로드 중" }
    }

    /** Match tools/downloader_engine.display_size (base 1000) + "/s". */
    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return ""
        return "${displaySize(bytesPerSec)}/s"
    }

    private fun displaySize(numBytes: Long): String {
        if (numBytes <= 0L) return ""
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = numBytes.toDouble()
        var unitIndex = 0
        while (value >= SIZE_UNIT_BASE && unitIndex < units.lastIndex) {
            value /= SIZE_UNIT_BASE
            unitIndex++
        }
        return if (units[unitIndex] == "B") {
            "${numBytes.toInt()} B"
        } else {
            String.format("%.1f %s", value, units[unitIndex])
        }
    }

    private fun formatEta(seconds: Long): String {
        if (seconds <= 0L) return ""
        val total = seconds.toInt()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%d:%02d", minutes, secs)
        }
    }

    private fun findFinishedMedia(workDir: File, audioFormat: String): File? {
        val candidates = workDir.walkTopDown()
            .filter { it.isFile && it.length() > 1024 }
            .filter { file ->
                val name = file.name.lowercase()
                if (name.endsWith(".ytdl") || name.contains("-frag") || name.contains(".part-frag")) {
                    return@filter false
                }
                if (audioFormat.isNotBlank()) {
                    return@filter file.extension.equals(audioFormat, true) ||
                        name.endsWith(".$audioFormat.part")
                }
                val ext = file.extension.lowercase()
                ext in setOf("mp4", "mkv", "webm", "mov", "ts", "m4a") ||
                    name.endsWith(".mp4.part") ||
                    name.endsWith(".mkv.part") ||
                    name.endsWith(".ts.part")
            }
            .sortedByDescending { it.lastModified() }
            .toList()
        val best = candidates.firstOrNull() ?: return null
        // Promote leftover yt-dlp temp names into a real media file.
        if (best.name.endsWith(".part", ignoreCase = true) && !best.name.contains("-Frag", ignoreCase = true)) {
            val promoted = File(
                best.parentFile,
                best.name.removeSuffix(".part").removeSuffix(".PART").let { base ->
                    if (base.contains('.')) base else "$base.mp4"
                },
            )
            if (best.renameTo(promoted)) return promoted
        }
        return best
    }

    private fun downloadDirectMp4(mediaUrl: String, workDir: File, referer: String = ""): File {
        val stem = URL(mediaUrl).path.substringAfterLast('/').substringBeforeLast('.').ifBlank { "video" }
            .replace(Regex("[\\/:*?\"<>|]"), "_")
        val part = File(workDir, "$stem.mp4.part")
        val output = File(workDir, "$stem.mp4")
        val existing = part.length()
        val connection = (URL(mediaUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", BrowserMediaFallback.DESKTOP_CHROME_UA)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Connection", "keep-alive")
            val ref = referer.ifBlank {
                runCatching { "https://${URL(mediaUrl).host}/" }.getOrDefault("")
            }
            if (ref.isNotBlank()) {
                setRequestProperty("Referer", ref)
                runCatching {
                    val host = URI(ref).host
                    if (!host.isNullOrBlank()) setRequestProperty("Origin", "https://$host")
                }
            }
            cookieStore.cookieHeaderFor(mediaUrl).ifBlank {
                cookieStore.cookieHeaderFor(ref)
            }.takeIf(String::isNotBlank)?.let {
                setRequestProperty("Cookie", it)
            }
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            connect()
        }
        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            error("HTTP ${connection.responseCode}: 직접 영상 요청이 거부되었습니다.")
        }
        val append = existing > 0 && connection.responseCode == HttpURLConnection.HTTP_PARTIAL
        val downloadedBefore = if (append) existing else 0L
        val total = connection.contentLengthLong.takeIf { it > 0 }?.plus(downloadedBefore) ?: 0L
        var downloaded = downloadedBefore
        connection.inputStream.use { input ->
            FileOutputStream(part, append).use { file ->
                val buffer = ByteArray(256 * 1024)
                val startedAt = System.currentTimeMillis()
                var lastPublishAt = 0L
                var lastPublishedBytes = downloaded
                while (true) {
                    if (isStopped) error("일시정지됨")
                    val count = input.read(buffer)
                    if (count < 0) break
                    file.write(buffer, 0, count)
                    downloaded += count
                    val now = System.currentTimeMillis()
                    if (now - lastPublishAt < 250L && downloaded - lastPublishedBytes < 256 * 1024) {
                        continue
                    }
                    val elapsedSec = ((now - startedAt).coerceAtLeast(1L)) / 1000.0
                    val speedBps = ((downloaded - downloadedBefore) / elapsedSec).toLong()
                    val percent = if (total > 0) (downloaded * 100 / total).toInt().coerceIn(0, 99) else 0
                    val detail = progressText(
                        percent = percent,
                        speedText = formatSpeed(speedBps),
                        etaText = "",
                        finishing = false,
                        fallback = "다운로드 중",
                    )
                    publishProgress(percent, detail, false)
                    lastPublishAt = now
                    lastPublishedBytes = downloaded
                }
            }
        }
        connection.disconnect()
        if (output.exists()) output.delete()
        if (!part.renameTo(output)) {
            part.copyTo(output, overwrite = true)
            part.delete()
        }
        publishProgress(100, "마무리 중", true)
        return output
    }

    private data class ParsedProgress(
        val percent: Int,
        val speed: String = "",
        val eta: String = "",
        val finishing: Boolean = false,
    )

    /**
     * yt-dlp HLS often reports progress=0 in the callback; scrape the log line and/or
     * estimate from growing work-dir files so the UI can show % and speed.
     */
    private fun parseProgress(
        line: String,
        callbackProgress: Float,
        expectedBytes: Long,
        workDir: File,
    ): ParsedProgress {
        val text = line.trim()
        val percentFromCallback = callbackProgress.toInt().coerceIn(0, 100)
        val percentFromLine = Regex("""(\d{1,3}(?:\.\d+)?)%""").find(text)
            ?.groupValues?.getOrNull(1)
            ?.toFloatOrNull()
            ?.toInt()
            ?.coerceIn(0, 100)
            ?: 0
        // Normalize "3.3MiB/s" / "3.3 MiB/s" → desktop-like "3.5 MB/s" when possible.
        val rawSpeed = Regex(
            """(\d+(?:\.\d+)?)\s*([KMGT]i?B)/s""",
            RegexOption.IGNORE_CASE,
        ).find(text)
        val speed = if (rawSpeed != null) {
            val n = rawSpeed.groupValues[1].toDoubleOrNull() ?: 0.0
            val unit = rawSpeed.groupValues[2].uppercase()
            val bytes = when (unit) {
                "B" -> n
                "KB", "KIB" -> n * if (unit == "KIB") 1024.0 else 1000.0
                "MB", "MIB" -> n * if (unit == "MIB") 1024.0 * 1024.0 else 1000.0 * 1000.0
                "GB", "GIB" -> n * if (unit == "GIB") 1024.0 * 1024.0 * 1024.0 else 1e9
                else -> 0.0
            }
            if (bytes > 0) formatSpeed(bytes.toLong()) else "${rawSpeed.groupValues[1]} ${rawSpeed.groupValues[2]}/s"
        } else {
            ""
        }
        val eta = Regex("""ETA\s+(\d+:\d+(?::\d+)?)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1).orEmpty()
        val finishing = text.contains("Merging", ignoreCase = true) ||
            text.contains("Fixing", ignoreCase = true) ||
            text.contains("Destination", ignoreCase = true) ||
            text.contains("after_move", ignoreCase = true) ||
            text.contains("finalizing", ignoreCase = true)
        var percent = maxOf(percentFromCallback, percentFromLine)
        if (percent <= 0 && expectedBytes > 0) {
            val written = workDirDownloadedBytes(workDir)
            if (written > 0) {
                percent = ((written * 100L) / expectedBytes).toInt().coerceIn(0, 99)
            }
        }
        return ParsedProgress(percent = percent, speed = speed, eta = eta, finishing = finishing)
    }

    private fun publishProgress(percent: Int, detail: String, finishing: Boolean) {
        val next = percent.coerceIn(0, 100)
        // Never go backwards except when finishing completes.
        val merged = if (finishing) {
            next
        } else {
            maxOf(next, progressPercent.get())
        }
        progressPercent.set(merged)
        progressDetail.set(detail)
        progressFinishing.set(finishing)
        Log.i(PROGRESS_LOG_TAG, "p=$merged finishing=$finishing detail=$detail")
        setProgressAsync(workDataOf(PROGRESS to merged, DETAIL to detail, FINISHING to finishing))
        // Foreground update is relatively expensive; still needed for notification %.
        setForegroundAsync(notificationInfo(merged, detail))
    }

    private fun saveOutput(source: File, treeUri: String): Pair<String, String> {
        val extension = source.extension.lowercase().ifBlank { "mp4" }
        val mimeType = when (extension) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            else -> "video/mp4"
        }
        val displayName = source.nameWithoutExtension + ".$extension"
        if (treeUri.isNotBlank()) {
            val root = DocumentFile.fromTreeUri(applicationContext, treeUri.toUri())
                ?: error("선택한 저장 폴더를 열 수 없습니다.")
            val target = root.createFile(mimeType, displayName)
                ?: error("선택한 폴더에 파일을 만들 수 없습니다.")
            applicationContext.contentResolver.openOutputStream(target.uri, "w")!!.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
            return (target.name ?: source.name) to target.uri.toString()
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ClipFlow")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = applicationContext.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("다운로드 폴더에 파일을 만들 수 없습니다.")
        try {
            resolver.openOutputStream(uri, "w")!!.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
        return displayName to uri.toString()
    }

    private fun notificationInfo(progress: Int, detail: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ClipFlow 다운로드", NotificationManager.IMPORTANCE_LOW),
        )
        val intent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ClipFlow")
            .setContentText(detail)
            .setContentIntent(intent)
            .setOnlyAlertOnce(true)
            .setOngoing(progress < 100)
            .setProgress(100, progress, progress <= 0)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID_BASE + (id.hashCode() and 0x0fff),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun errorData(message: String): Data = workDataOf(ERROR to message.take(500))

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(20)

    companion object {
        const val KEY_URL = "url"
        const val KEY_DIRECT_URL = "direct_url"
        const val KEY_PREFER_DIRECT = "prefer_direct"
        const val KEY_REFERER = "referer"
        const val KEY_FORMAT = "format"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_CONCURRENCY = "concurrency"
        const val KEY_START = "start"
        const val KEY_END = "end"
        const val KEY_EXACT_CUT = "exact_cut"
        const val KEY_AUDIO_FORMAT = "audio_format"
        const val KEY_TASK_KEY = "task_key"
        const val KEY_TITLE = "title"
        const val KEY_EXPECTED_BYTES = "expected_bytes"
        const val PROGRESS = "progress"
        const val DETAIL = "detail"
        const val FINISHING = "finishing"
        const val OUTPUT_NAME = "output_name"
        const val OUTPUT_URI = "output_uri"
        const val ERROR = "error"
        private const val FILE_MARKER = "__CLIPFLOW_FILE__"
        private const val CHANNEL_ID = "clipflow_downloads"
        private const val NOTIFICATION_ID_BASE = 21000
        /** Match tools/downloader_engine.SIZE_UNIT_BASE on non-Windows. */
        private const val SIZE_UNIT_BASE = 1000.0
        private const val PROGRESS_LOG_TAG = "ClipFlowProgress"
        /** Match tools/downloader_engine.YTDLP_CONCURRENT_FRAGMENT_DOWNLOADS. */
        private const val HLS_FRAGMENT_CONCURRENCY = 16
    }
}
