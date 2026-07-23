package app.clipflow.android.data

import android.content.Context
import app.clipflow.android.ClipFlowApplication
import app.clipflow.android.model.MediaCandidate
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class AnalysisResult(
    val title: String,
    val candidates: List<MediaCandidate>,
)

class YoutubeDlAnalyzer(private val context: Context) {
    private val cookieStore = CookieFileStore(context)

    fun analyze(url: String): AnalysisResult {
        directMp4Candidate(url)?.let { return AnalysisResult(it.title, listOf(it)) }
        (context.applicationContext as ClipFlowApplication).ensureEngine()
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--no-cache-dir")
            cookieStore.activeFile()?.let { addOption("--cookies", it.absolutePath) }
        }
        val response = YoutubeDL.getInstance().execute(request)
        val root = JSONObject(response.out.trim())
        val title = root.optString("title").ifBlank { "제목 없는 영상" }
        val uploader = root.optString("uploader")
        val thumbnail = root.optString("thumbnail")
        val duration = root.optDouble("duration", 0.0).toInt()
        val formats = root.optJSONArray("formats")
        val candidates = buildList {
            if (formats != null) {
                for (index in 0 until formats.length()) {
                    val item = formats.optJSONObject(index) ?: continue
                    val formatId = item.optString("format_id")
                    val mediaUrl = item.optString("url")
                    val videoCodec = item.optString("vcodec")
                    val extension = item.optString("ext")
                    if (formatId.isBlank() || mediaUrl.isBlank()) continue
                    if (videoCodec.isBlank() || videoCodec == "none") continue
                    if (extension in setOf("mhtml", "html")) continue
                    val protocol = item.optString("protocol")
                    val manifestUrl = item.optString("manifest_url")
                    add(
                        MediaCandidate(
                            id = "$formatId-$index",
                            sourceUrl = url,
                            mediaUrl = mediaUrl,
                            title = title,
                            uploader = uploader,
                            thumbnailUrl = thumbnail,
                            formatId = formatId,
                            extension = extension.ifBlank { "mp4" },
                            width = item.optInt("width", 0),
                            height = item.optInt("height", 0),
                            fps = item.optDouble("fps", 0.0).toInt(),
                            videoCodec = videoCodec,
                            audioCodec = item.optString("acodec"),
                            dynamicRange = item.optString("dynamic_range"),
                            sizeBytes = item.optLong("filesize", 0L)
                                .takeIf { it > 0 }
                                ?: item.optLong("filesize_approx", 0L),
                            durationSeconds = duration,
                            isManifest = manifestUrl.isNotBlank() ||
                                protocol.contains("m3u8", true) ||
                                protocol.contains("dash", true),
                        ),
                    )
                }
            }
        }.ifEmpty {
            val mediaUrl = root.optString("url")
            if (mediaUrl.isBlank()) emptyList() else listOf(
                MediaCandidate(
                    id = root.optString("format_id", "best"),
                    sourceUrl = url,
                    mediaUrl = mediaUrl,
                    title = title,
                    uploader = uploader,
                    thumbnailUrl = thumbnail,
                    formatId = root.optString("format_id", "best"),
                    extension = root.optString("ext", "mp4"),
                    width = root.optInt("width", 0),
                    height = root.optInt("height", 0),
                    fps = root.optDouble("fps", 0.0).toInt(),
                    videoCodec = root.optString("vcodec"),
                    audioCodec = root.optString("acodec"),
                    dynamicRange = root.optString("dynamic_range"),
                    sizeBytes = root.optLong("filesize", 0L),
                    durationSeconds = duration,
                    isManifest = mediaUrl.contains(".m3u8", true) || mediaUrl.contains(".mpd", true),
                ),
            )
        }
        if (candidates.isEmpty()) error("다운로드 가능한 영상 형식을 찾지 못했습니다.")
        return AnalysisResult(title, candidates)
    }

    private fun directMp4Candidate(url: String): MediaCandidate? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.path.orEmpty().substringAfterLast('.').equals("mp4", true)) return null
        val filename = uri.path.substringAfterLast('/').substringBeforeLast('.').ifBlank { "video" }
        val size = runCatching {
            (URL(url).openConnection() as HttpURLConnection).run {
                requestMethod = "HEAD"
                instanceFollowRedirects = true
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", BROWSER_USER_AGENT)
                cookieStore.cookieHeaderFor(url).takeIf(String::isNotBlank)?.let {
                    setRequestProperty("Cookie", it)
                }
                connect()
                contentLengthLong.coerceAtLeast(0L).also { disconnect() }
            }
        }.getOrDefault(0L)
        return MediaCandidate(
            id = "direct-mp4",
            sourceUrl = url,
            mediaUrl = url,
            title = filename.replace('_', ' ').replace('-', ' '),
            uploader = uri.host.orEmpty(),
            thumbnailUrl = "",
            formatId = "direct",
            extension = "mp4",
            width = 0,
            height = 0,
            fps = 0,
            videoCodec = "unknown",
            audioCodec = "unknown",
            dynamicRange = "",
            sizeBytes = size,
            durationSeconds = 0,
            isManifest = false,
        )
    }

    companion object {
        // Some CDNs challenge fabricated full Chrome versions while accepting a
        // neutral browser token for direct media requests.
        const val BROWSER_USER_AGENT = "Mozilla/5.0"
    }
}
