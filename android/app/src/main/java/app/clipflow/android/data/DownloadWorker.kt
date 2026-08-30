package app.clipflow.android.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
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
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
        val outputFormat = inputData.getString(KEY_OUTPUT_FORMAT).orEmpty().lowercase().ifBlank { "mp4" }
        val treeUri = inputData.getString(KEY_TREE_URI).orEmpty()
        val playlistTitle = inputData.getString(KEY_PLAYLIST_TITLE).orEmpty()
        val audioFormat = inputData.getString(KEY_AUDIO_FORMAT).orEmpty().lowercase()
        val concurrency = inputData.getInt(KEY_CONCURRENCY, 16).coerceIn(1, 16)
        if (sourceUrl.isBlank()) return Result.failure(errorData("URL이 비어 있습니다."))

        val preparingText = if (audioFormat.isNotBlank()) "음원 추출 준비 중" else "다운로드 준비 중"
        setForegroundAsync(notificationInfo(0, preparingText)).get()
        val taskKey = inputData.getString(KEY_TASK_KEY).orEmpty().ifBlank { hash("$sourceUrl|$formatSelector|$outputFormat") }
        val workDir = File(applicationContext.filesDir, "downloads/$taskKey").apply { mkdirs() }

        return try {
            val preferDirect = inputData.getBoolean(KEY_PREFER_DIRECT, false)
            val isManifest = looksLikeManifest(directUrl)
            val output = if (
                audioFormat.isBlank() &&
                directUrl.isNotBlank() &&
                (preferDirect || isManifest)
            ) {
                if (isManifest) {
                    downloadWithYoutubeDl(
                        directUrl,
                        "best",
                        concurrency,
                        workDir,
                        audioFormat,
                        outputFormat,
                        referer = referer,
                    )
                } else if (clipSection() != null) {
                    downloadWithYoutubeDl(
                        directUrl,
                        "best",
                        concurrency,
                        workDir,
                        audioFormat,
                        outputFormat,
                        referer = referer,
                    )
                } else {
                    runCatching { downloadDirectMedia(directUrl, workDir, referer = referer, outputFormat = outputFormat) }
                        .getOrElse { directError ->
                            publishProgress(0, "직접 요청 실패 · yt-dlp로 재시도", false)
                            runCatching {
                                // Retry the concrete media URL first. It preserves browser-captured
                                // signed URLs while still letting yt-dlp handle remux/headers.
                                downloadWithYoutubeDl(
                                    directUrl,
                                    "best",
                                    concurrency,
                                    workDir,
                                    audioFormat,
                                    outputFormat,
                                    referer = referer,
                                )
                            }.recoverCatching {
                                // Some direct URLs expire; page re-extraction mirrors desktop fallback.
                                downloadWithYoutubeDl(
                                    sourceUrl,
                                    formatSelector,
                                    concurrency,
                                    workDir,
                                    audioFormat,
                                    outputFormat,
                                    referer = referer,
                                )
                            }.getOrElse { ytdlpError ->
                                throw IllegalStateException(
                                    buildString {
                                        append("직접 요청 실패: ")
                                        append(directError.message ?: directError::class.java.simpleName)
                                        append("\nyt-dlp 재시도 실패: ")
                                        append(ytdlpError.message ?: ytdlpError::class.java.simpleName)
                                    },
                                    ytdlpError,
                                )
                            }
                        }
                }
            } else {
                downloadWithYoutubeDl(
                    sourceUrl,
                    formatSelector,
                    concurrency,
                    workDir,
                    audioFormat,
                    outputFormat,
                    referer = referer,
                )
            }

            if (!output.isFile || output.length() <= 0L) {
                error("다운로드 결과 파일이 비어 있습니다.")
            }
            setProgressAsync(workDataOf(PROGRESS to 100, DETAIL to "파일 저장 중", FINISHING to true)).get()
            val sourceBytes = output.length()
            val sourceDurationSeconds = readDurationSeconds(output)
            val (savedName, savedUri) = saveOutput(output, treeUri, playlistTitle)
            val savedBytes = try {
                verifySavedOutput(savedUri, sourceBytes)
            } catch (error: Throwable) {
                deleteSavedOutput(savedUri)
                throw error
            }
            val durationSeconds = readDurationSeconds(savedUri).takeIf { it > 0 } ?: sourceDurationSeconds
            output.delete()
            runCatching { workDir.deleteRecursively() }
            Result.success(
                workDataOf(
                    OUTPUT_NAME to savedName,
                    OUTPUT_URI to savedUri,
                    OUTPUT_BYTES to savedBytes,
                    OUTPUT_DURATION_SECONDS to durationSeconds,
                    OUTPUT_FORMAT_SELECTOR to formatSelector,
                ),
            )
        } catch (error: Throwable) {
            Log.e(PROGRESS_LOG_TAG, "download failed", error)
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

    private fun looksLikeManifest(url: String): Boolean =
        url.contains(".m3u8", ignoreCase = true) ||
            url.contains(".mpd", ignoreCase = true) ||
            url.contains("/media/hls", ignoreCase = true) ||
            url.contains("/manifest/", ignoreCase = true) ||
            url.contains("gcdn.app", ignoreCase = true)

    private fun downloadWithYoutubeDl(
        sourceUrl: String,
        formatSelector: String,
        concurrency: Int,
        workDir: File,
        audioFormat: String,
        outputFormat: String,
        referer: String = "",
    ): File {
        (applicationContext as ClipFlowApplication).ensureEngine()
        val isHls = looksLikeManifest(sourceUrl)
        fun buildRequest(proxyUrl: String?): YoutubeDLRequest = YoutubeDLRequest(sourceUrl).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--continue")
            addOption("--newline")
            addOption("--no-warnings")
            addOption("--retries", "10")
            addOption("--fragment-retries", "10")
            // Desktop ClipFlow lets yt-dlp own the transport. Do not force aria2 for
            // every progressive download: native-library/extractor mismatches then
            // cannot prevent an otherwise valid yt-dlp download.
            addOption("--concurrent-fragments", concurrency.coerceIn(1, 16))
            addOption("--http-chunk-size", "10485760")
            cookieStore.ytDlpCookieFileFor(sourceUrl)?.let { addOption("--cookies", it.absolutePath) }
            proxyUrl?.let { addOption("--proxy", it) }
            addOption("--user-agent", BrowserMediaFallback.DESKTOP_CHROME_UA)
            if (referer.isNotBlank()) {
                addOption("--referer", referer)
                addOption("--add-header", "Referer:$referer")
                runCatching {
                    val parsed = URI(referer)
                    if (!parsed.scheme.isNullOrBlank() && !parsed.host.isNullOrBlank()) {
                        addOption("--add-header", "Origin:${parsed.scheme}://${parsed.host}")
                    }
                }
            }
            if (audioFormat.isNotBlank()) {
                addOption("--format", "bestaudio/best")
                addOption("--extract-audio")
                addOption("--audio-format", audioFormat)
            } else {
                addOption("--format", formatSelector)
                if (formatSelector.startsWith("best", ignoreCase = true)) {
                    addOption("--format-sort", "vcodec:h264,quality,res,fps,hdr:12,acodec:aac")
                }
                addOption("--merge-output-format", outputFormat)
                // Remux only; desktop does not re-encode every MP4 download either.
                addOption("--remux-video", outputFormat)
                if (isHls && outputFormat == "mp4") {
                    addOption("--fixup", "never")
                }
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
        val stopTicker = AtomicBoolean(false)
        val ticker = startProgressTicker(workDir, expectedBytes, stopTicker)
        try {
            val callback = { progress: Float, etaInSeconds: Long, line: String ->
                val parsed = parseProgress(line, progress, expectedBytes, workDir)
                val percent = maxOf(parsed.percent, progressPercent.get()).coerceIn(0, 100)
                val etaText = if (etaInSeconds > 0) formatEta(etaInSeconds) else parsed.eta
                val detail = progressText(
                    percent = percent,
                    speedText = parsed.speed,
                    etaText = etaText,
                    finishing = percent >= 100 || parsed.finishing,
                    fallback = progressDetail.get(),
                )
                publishProgress(percent, detail, percent >= 100 || parsed.finishing)
            }
            val response = try {
                YoutubeDL.getInstance().execute(buildRequest(null), processId, callback)
            } catch (initial: Throwable) {
                if (!TlsFragmentingProxy.isConnectionTermination(initial)) throw initial
                publishProgress(progressPercent.get(), "보호 연결로 다시 시도 중", false)
                try {
                    YoutubeDL.getInstance().execute(
                        buildRequest(TlsFragmentingProxy.endpoint(applicationContext)),
                        processId,
                        callback,
                    )
                } catch (retry: Throwable) {
                    retry.addSuppressed(initial)
                    throw retry
                }
            }
            val marked = response.out.lineSequence()
                .lastOrNull { it.startsWith(FILE_MARKER) }
                ?.removePrefix(FILE_MARKER)
                ?.trim()
                ?.let(::File)
                ?.takeIf { it.isFile && it.length() > 0 }
            val completed = marked ?: findFinishedMedia(workDir, audioFormat)
            if (completed != null) {
                return remuxHlsToMp4IfNeeded(completed, isHls, outputFormat, audioFormat)
            }
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
                if (errTail.isNotBlank()) "완성된 미디어 파일을 만들지 못했습니다: $errTail"
                else "완성된 미디어 파일을 찾지 못했습니다.",
            )
        } finally {
            stopTicker.set(true)
            ticker.interrupt()
            runCatching { ticker.join(800) }
        }
    }

    private fun remuxHlsToMp4IfNeeded(
        source: File,
        isHls: Boolean,
        outputFormat: String,
        audioFormat: String,
    ): File {
        if (!isHls || outputFormat != "mp4" || audioFormat.isNotBlank()) return source

        publishProgress(100, "MP4 마무리 중", true)
        val nativeDir = applicationContext.applicationInfo.nativeLibraryDir
        val packages = File(applicationContext.noBackupFilesDir, "youtubedl-android/packages")
        val pythonLib = File(packages, "python/usr/lib")
        val ffmpegLib = File(packages, "ffmpeg/usr/lib")
        val ffmpeg = File(nativeDir, "libffmpeg.so")
        check(ffmpeg.isFile) { "번들 FFmpeg를 찾지 못했습니다." }

        val target = File(source.parentFile, "${source.nameWithoutExtension}.mp4")
        val temporary = File(source.parentFile, ".${source.nameWithoutExtension}.remux.mp4")
        temporary.delete()
        val process = ProcessBuilder(
            ffmpeg.absolutePath,
            "-nostdin",
            "-loglevel",
            "error",
            "-y",
            "-i",
            source.absolutePath,
            "-map",
            "0:v:0?",
            "-map",
            "0:a:0?",
            "-c",
            "copy",
            "-movflags",
            "+faststart",
            temporary.absolutePath,
        ).redirectErrorStream(true).apply {
            environment()["LD_LIBRARY_PATH"] = listOf(pythonLib, ffmpegLib)
                .joinToString(":") { it.absolutePath }
        }.start()
        val log = StringBuilder()
        val reader = thread(name = "clipflow-ffmpeg-log", isDaemon = true) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (log.length < 8_192) log.appendLine(line)
                }
            }
        }
        while (process.isAlive) {
            if (isStopped) {
                process.destroyForcibly()
                reader.join(1_000)
                temporary.delete()
                error("일시정지됨")
            }
            Thread.sleep(100)
        }
        reader.join(1_000)
        check(process.exitValue() == 0 && temporary.isFile && temporary.length() > 0L) {
            log.toString().trim().ifBlank { "MP4 컨테이너 변환에 실패했습니다." }
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        if (source.absolutePath != target.absolutePath) source.delete()
        return target
    }

    private fun startProgressTicker(
        workDir: File,
        expectedBytes: Long,
        stop: AtomicBoolean,
    ): Thread {
        val lastBytes = AtomicLong(0L)
        val lastAt = AtomicLong(System.currentTimeMillis())
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
                        prevSmooth > 0L -> (prevSmooth * 3L) / 4L
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
                !n.endsWith(".ytdl") &&
                    !n.endsWith(".json") &&
                    !n.endsWith(".vtt") &&
                    !n.endsWith(".srt") &&
                    it.length() > 0L
            }
            .sumOf { it.length() }
    }

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
        return workDir.walkTopDown()
            .filter { it.isFile && it.length() > 1024 }
            .filter { file ->
                val name = file.name.lowercase()
                if (name.endsWith(".part") || name.endsWith(".ytdl") || name.contains("-frag") || name.contains(".part-frag")) {
                    return@filter false
                }
                if (audioFormat.isNotBlank()) {
                    return@filter file.extension.equals(audioFormat, true)
                }
                file.extension.lowercase() in COMPLETED_VIDEO_EXTENSIONS
            }
            .maxByOrNull { it.lastModified() }
    }

    private fun downloadDirectMedia(
        mediaUrl: String,
        workDir: File,
        referer: String = "",
        outputFormat: String = "mp4",
    ): File {
        val url = URL(mediaUrl)
        val sourceExtension = url.path.substringAfterLast('.', "").lowercase()
            .takeIf { it in COMPLETED_VIDEO_EXTENSIONS }
            ?: outputFormat.takeIf { it in COMPLETED_VIDEO_EXTENSIONS }
            ?: "mp4"
        val stem = url.path.substringAfterLast('/').substringBeforeLast('.').ifBlank { "video" }
            .replace(Regex("[\\/:*?\"<>|]"), "_")
        val part = File(workDir, "$stem.$sourceExtension.part")
        val output = File(workDir, "$stem.$sourceExtension")
        val existing = part.length()
        fun configure(connection: HttpURLConnection) = connection.apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", BrowserMediaFallback.DESKTOP_CHROME_UA)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Connection", "keep-alive")
            val ref = referer.ifBlank {
                runCatching { "https://${url.host}/" }.getOrDefault("")
            }
            if (ref.isNotBlank()) {
                setRequestProperty("Referer", ref)
                runCatching {
                    val parsed = URI(ref)
                    if (!parsed.scheme.isNullOrBlank() && !parsed.host.isNullOrBlank()) {
                        setRequestProperty("Origin", "${parsed.scheme}://${parsed.host}")
                    }
                }
            }
            cookieStore.cookieHeaderFor(mediaUrl).ifBlank {
                cookieStore.cookieHeaderFor(ref)
            }.takeIf(String::isNotBlank)?.let {
                setRequestProperty("Cookie", it)
            }
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        var regular: HttpURLConnection? = null
        val connection = try {
            (url.openConnection() as HttpURLConnection).also {
                regular = it
                configure(it).connect()
                it.responseCode
            }
        } catch (initial: Throwable) {
            regular?.disconnect()
            if (!TlsFragmentingProxy.isConnectionTermination(initial) || !mediaUrl.startsWith("https://")) {
                throw initial
            }
            publishProgress(progressPercent.get(), "보호 연결로 다시 시도 중", false)
            HttpsHostRouting.connect(mediaUrl) { configure(it) }
        }
        if (connection.responseCode !in 200..299) {
            val code = connection.responseCode
            connection.disconnect()
            error("HTTP $code: 직접 영상 요청이 거부되었습니다.")
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
        if (downloaded <= 0L) error("직접 다운로드 결과가 비어 있습니다.")
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
        if (!finishing && progressFinishing.get()) return
        val next = percent.coerceIn(0, 100)
        val merged = if (finishing) next else maxOf(next, progressPercent.get())
        progressPercent.set(merged)
        progressDetail.set(detail)
        progressFinishing.set(finishing)
        Log.i(PROGRESS_LOG_TAG, "p=$merged finishing=$finishing detail=$detail")
        setProgressAsync(workDataOf(PROGRESS to merged, DETAIL to detail, FINISHING to finishing))
        setForegroundAsync(notificationInfo(merged, detail))
    }

    private fun saveOutput(source: File, treeUri: String, playlistTitle: String): Pair<String, String> {
        val extension = source.extension.lowercase().ifBlank { "mp4" }
        val mimeType = mimeTypeFor(extension)
        val displayName = source.nameWithoutExtension + ".$extension"
        val playlistFolder = safeFolderName(playlistTitle)
        if (treeUri.isNotBlank()) {
            val root = DocumentFile.fromTreeUri(applicationContext, treeUri.toUri())
                ?: error("선택한 저장 폴더를 열 수 없습니다.")
            val outputDir = if (playlistFolder.isBlank() || root.name == playlistFolder) {
                root
            } else {
                root.findFile(playlistFolder)?.takeIf { it.isDirectory }
                    ?: root.createDirectory(playlistFolder)
                    ?: error("재생목록 폴더를 만들 수 없습니다.")
            }
            val target = outputDir.createFile(mimeType, displayName)
                ?: error("선택한 폴더에 파일을 만들 수 없습니다.")
            val stream = applicationContext.contentResolver.openOutputStream(target.uri, "w")
                ?: error("선택한 폴더의 출력 스트림을 열 수 없습니다.")
            stream.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
            return (target.name ?: source.name) to target.uri.toString()
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/ClipFlow" +
                    (if (playlistFolder.isNotBlank()) "/$playlistFolder" else ""),
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = applicationContext.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("다운로드 폴더에 파일을 만들 수 없습니다.")
        try {
            val stream = resolver.openOutputStream(uri, "w")
                ?: error("다운로드 폴더의 출력 스트림을 열 수 없습니다.")
            stream.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) <= 0) {
                error("저장된 파일을 공개 상태로 전환하지 못했습니다.")
            }
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
        return displayName to uri.toString()
    }

    private fun safeFolderName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .trimEnd('.')
        .take(80)

    private fun verifySavedOutput(savedUri: String, sourceBytes: Long): Long {
        val uri = savedUri.toUri()
        val resolver = applicationContext.contentResolver
        val descriptorLength = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (descriptorLength == 0L) {
            error("저장된 파일 크기가 0바이트입니다.")
        }
        val readable = resolver.openInputStream(uri)?.use { it.read() >= 0 } ?: false
        if (!readable) {
            error("저장된 파일을 다시 읽을 수 없습니다.")
        }
        // Many document providers report UNKNOWN_LENGTH (-1). When a real length is
        // available, require a non-empty target and catch obviously truncated copies.
        if (sourceBytes > 0L && descriptorLength > 0L && descriptorLength < sourceBytes) {
            error("저장된 파일이 완전하지 않습니다. (${descriptorLength}/${sourceBytes} bytes)")
        }
        return descriptorLength.takeIf { it > 0L } ?: sourceBytes
    }

    private fun deleteSavedOutput(savedUri: String) {
        val uri = savedUri.toUri()
        val resolver = applicationContext.contentResolver
        val deleted = runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
        if (!deleted) runCatching { DocumentFile.fromSingleUri(applicationContext, uri)?.delete() }
    }

    private fun mimeTypeFor(extension: String): String = when (extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        "m4a" -> "audio/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "m4v" -> "video/x-m4v"
        "ts" -> "video/mp2t"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
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

    private fun readDurationSeconds(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: return 0
            ((durationMs + 500L) / 1000L).toInt().coerceAtLeast(0)
        } catch (_: Throwable) {
            0
        } finally {
            retriever.release()
        }
    }

    private fun readDurationSeconds(uriText: String): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(applicationContext, uriText.toUri())
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: return 0
            ((durationMs + 500L) / 1000L).toInt().coerceAtLeast(0)
        } catch (_: Throwable) {
            0
        } finally {
            retriever.release()
        }
    }

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
        const val KEY_OUTPUT_FORMAT = "output_format"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_CONCURRENCY = "concurrency"
        const val KEY_START = "start"
        const val KEY_END = "end"
        const val KEY_EXACT_CUT = "exact_cut"
        const val KEY_AUDIO_FORMAT = "audio_format"
        const val KEY_TASK_KEY = "task_key"
        const val KEY_TITLE = "title"
        const val KEY_PLAYLIST_TITLE = "playlist_title"
        const val KEY_EXPECTED_BYTES = "expected_bytes"
        const val PROGRESS = "progress"
        const val DETAIL = "detail"
        const val FINISHING = "finishing"
        const val OUTPUT_NAME = "output_name"
        const val OUTPUT_URI = "output_uri"
        const val OUTPUT_BYTES = "output_bytes"
        const val OUTPUT_DURATION_SECONDS = "output_duration_seconds"
        const val OUTPUT_FORMAT_SELECTOR = "output_format_selector"
        const val ERROR = "error"
        private const val FILE_MARKER = "__CLIPFLOW_FILE__"
        private const val CHANNEL_ID = "clipflow_downloads"
        private const val NOTIFICATION_ID_BASE = 21000
        private const val SIZE_UNIT_BASE = 1000.0
        private const val PROGRESS_LOG_TAG = "ClipFlowProgress"
        private val COMPLETED_VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "m4v", "ts")
    }
}
