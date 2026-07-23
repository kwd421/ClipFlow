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

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    private val processId = id.toString()
    private val cookieStore = CookieFileStore(appContext)

    override fun doWork(): Result {
        val sourceUrl = inputData.getString(KEY_URL).orEmpty()
        val directUrl = inputData.getString(KEY_DIRECT_URL).orEmpty()
        val referer = inputData.getString(KEY_REFERER).orEmpty().ifBlank { sourceUrl }
        val formatSelector = inputData.getString(KEY_FORMAT).orEmpty().ifBlank { "best" }
        val treeUri = inputData.getString(KEY_TREE_URI).orEmpty()
        val audioFormat = inputData.getString(KEY_AUDIO_FORMAT).orEmpty().lowercase()
        val concurrency = inputData.getInt(KEY_CONCURRENCY, 3).coerceIn(1, 8)
        if (sourceUrl.isBlank()) return Result.failure(errorData("URL이 비어 있습니다."))

        val preparingText = if (audioFormat.isNotBlank()) "음원 추출 준비 중" else "다운로드 준비 중"
        setForegroundAsync(notificationInfo(0, preparingText)).get()
        val taskKey = inputData.getString(KEY_TASK_KEY).orEmpty().ifBlank { hash("$sourceUrl|$formatSelector") }
        val workDir = File(applicationContext.filesDir, "downloads/$taskKey").apply { mkdirs() }

        return try {
            val preferDirect = inputData.getBoolean(KEY_PREFER_DIRECT, false)
            val isManifest = directUrl.contains(".m3u8", ignoreCase = true) ||
                directUrl.contains(".mpd", ignoreCase = true) ||
                directUrl.contains("/media/hls", ignoreCase = true)
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
        val request = YoutubeDLRequest(sourceUrl).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--continue")
            addOption("--newline")
            addOption("--downloader", "libaria2c.so")
            addOption("--concurrent-fragments", concurrency)
            cookieStore.activeFile()?.let { addOption("--cookies", it.absolutePath) }
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
            } else {
                addOption("--format", formatSelector)
                addOption("--merge-output-format", "mp4")
                addOption("--recode-video", "mp4")
            }
            addOption("--paths", workDir.absolutePath)
            addOption("--output", "%(title).120s [%(id)s].%(ext)s")
            addOption("--print", "after_move:__CLIPFLOW_FILE__%(filepath)s")
            clipSection()?.let { section ->
                addOption("--download-sections", section)
                if (inputData.getBoolean(KEY_EXACT_CUT, false)) {
                    addOption("--force-keyframes-at-cuts")
                }
            }
        }
        val response = YoutubeDL.getInstance().execute(request, processId) { progress, eta, line ->
            val percent = progress.toInt().coerceIn(0, 100)
            val detail = when {
                percent >= 100 -> "마무리 중"
                eta > 0 -> "$percent% · ${eta}초 남음"
                line.isNotBlank() -> "$percent%"
                else -> "다운로드 중"
            }
            publishProgress(percent, detail, percent >= 100)
        }
        return response.out.lineSequence()
            .lastOrNull { it.startsWith(FILE_MARKER) }
            ?.removePrefix(FILE_MARKER)
            ?.trim()
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?: workDir.walkTopDown()
                .filter {
                    it.isFile && if (audioFormat.isNotBlank()) {
                        it.extension.equals(audioFormat, true)
                    } else {
                        it.extension.equals("mp4", true)
                    }
                }
                .maxByOrNull(File::lastModified)
            ?: error("완성된 MP4 파일을 찾지 못했습니다.")
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
                while (true) {
                    if (isStopped) error("일시정지됨")
                    val count = input.read(buffer)
                    if (count < 0) break
                    file.write(buffer, 0, count)
                    downloaded += count
                    val percent = if (total > 0) (downloaded * 100 / total).toInt().coerceIn(0, 99) else 0
                    publishProgress(percent, if (percent > 0) "$percent%" else "다운로드 중", false)
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

    private fun publishProgress(percent: Int, detail: String, finishing: Boolean) {
        setProgressAsync(workDataOf(PROGRESS to percent, DETAIL to detail, FINISHING to finishing))
        setForegroundAsync(notificationInfo(percent, detail))
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
        const val PROGRESS = "progress"
        const val DETAIL = "detail"
        const val FINISHING = "finishing"
        const val OUTPUT_NAME = "output_name"
        const val OUTPUT_URI = "output_uri"
        const val ERROR = "error"
        private const val FILE_MARKER = "__CLIPFLOW_FILE__"
        private const val CHANNEL_ID = "clipflow_downloads"
        private const val NOTIFICATION_ID_BASE = 21000
    }
}
