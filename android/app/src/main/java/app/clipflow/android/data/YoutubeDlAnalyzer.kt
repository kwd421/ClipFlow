package app.clipflow.android.data

import android.content.Context
import app.clipflow.android.ClipFlowApplication
import app.clipflow.android.model.MediaCandidate
import app.clipflow.android.model.RowKind
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class AnalysisResult(
    val title: String,
    val candidates: List<MediaCandidate>,
    val playlistEntries: List<PlaylistEntryRef> = emptyList(),
    val isPlaylist: Boolean = false,
    val route: String = "ytdlp",
)

data class PlaylistEntryRef(
    val index: Int,
    val url: String,
    val title: String,
    val thumbnailUrl: String = "",
    val durationSeconds: Int = 0,
)

class YoutubeDlAnalyzer(private val context: Context) {
    private val cookieStore = CookieFileStore(context)
    private val siteRouter = SiteRouter(cookieStore)
    fun analyze(url: String, allowBrowserFallback: Boolean = true): AnalysisResult {
        directMp4Candidate(url)?.let { return AnalysisResult(it.title, listOf(it), route = "direct") }

        siteRouter.analyzeIfKnown(url)?.let { return it.copy(route = siteRouter.routeName(url).ifBlank { it.route }) }

        val ytdlpError = runCatching { analyzeWithYoutubeDl(url) }
            .fold(onSuccess = { return it }, onFailure = { it })

        if (allowBrowserFallback && shouldTryBrowserFallback(url, ytdlpError.message.orEmpty())) {
            // Desktop 공용 폴백 = real browser + network capture.
            // Android: open a visible WebView activity (headless WebView dies on CF/AniLife).
            return runCatching {
                app.clipflow.android.ui.BrowserCaptureActivity.captureBlocking(
                    context = context.applicationContext,
                    cookieStore = cookieStore,
                    url = url,
                    timeoutSeconds = 120,
                )
            }.getOrElse { browserError ->
                val ytdlpMessage = friendlyAnalyzeError(ytdlpError.message.orEmpty())
                val browserMessage = browserError.message.orEmpty()
                error(
                    buildString {
                        append(ytdlpMessage)
                        if (browserMessage.isNotBlank()) {
                            append("\n")
                            append(browserMessage)
                        }
                    },
                )
            }
        }
        throw ytdlpError
    }

    private fun friendlyAnalyzeError(raw: String): String {
        val lower = raw.lowercase()
        return when {
            "ssl" in lower || "tls" in lower || "eof" in lower || "connection_closed" in lower ->
                "사이트가 yt-dlp 연결을 끊었습니다. 브라우저 폴백으로 전환합니다."
            "unsupported url" in lower || "no video formats" in lower ->
                "yt-dlp가 이 URL을 해석하지 못했습니다."
            raw.isBlank() -> "영상 분석에 실패했습니다."
            else -> raw.lineSequence().firstOrNull().orEmpty().ifBlank { "영상 분석에 실패했습니다." }
        }
    }

    fun analyzePlaylistShell(url: String): AnalysisResult {
        (context.applicationContext as ClipFlowApplication).ensureEngine()
        val request = YoutubeDLRequest(url).apply {
            addOption("--flat-playlist")
            addOption("--dump-single-json")
            addOption("--no-warnings")
            addOption("--no-cache-dir")
            cookieStore.activeFile()?.let { addOption("--cookies", it.absolutePath) }
            applySiteOptions(url)
        }
        val response = YoutubeDL.getInstance().execute(request)
        val root = JSONObject(response.out.trim())
        val type = root.optString("_type")
        val entries = root.optJSONArray("entries")
        if (type != "playlist" && entries == null) {
            return analyze(url)
        }
        val title = root.optString("title").ifBlank { "재생목록" }
        val playlistId = root.optString("id").ifBlank { hash(url) }
        val parentId = "playlist-$playlistId"
        val entryRefs = buildList {
            if (entries != null) {
                for (index in 0 until entries.length()) {
                    val item = entries.optJSONObject(index) ?: continue
                    val entryUrl = item.optString("url")
                        .ifBlank { item.optString("webpage_url") }
                        .ifBlank {
                            val id = item.optString("id")
                            if (id.isNotBlank() && url.contains("youtube", true)) {
                                "https://www.youtube.com/watch?v=$id"
                            } else ""
                        }
                    if (entryUrl.isBlank()) continue
                    add(
                        PlaylistEntryRef(
                            index = index,
                            url = entryUrl,
                            title = item.optString("title").ifBlank { "항목 ${index + 1}" },
                            thumbnailUrl = item.optString("thumbnail")
                                .ifBlank { item.optJSONArray("thumbnails")?.optJSONObject(0)?.optString("url").orEmpty() },
                            durationSeconds = item.optDouble("duration", 0.0).toInt(),
                        ),
                    )
                }
            }
        }
        val parent = MediaCandidate(
            id = parentId,
            sourceUrl = url,
            mediaUrl = "",
            title = title,
            uploader = root.optString("uploader"),
            thumbnailUrl = root.optString("thumbnail"),
            formatId = "playlist",
            extension = "playlist",
            width = 0,
            height = 0,
            fps = 0,
            videoCodec = "",
            audioCodec = "",
            dynamicRange = "",
            sizeBytes = 0,
            durationSeconds = entryRefs.sumOf { it.durationSeconds },
            isManifest = false,
            kind = RowKind.Playlist,
            itemCount = entryRefs.size,
            expanded = true,
            route = "playlist",
        )
        return AnalysisResult(
            title = title,
            candidates = listOf(parent),
            playlistEntries = entryRefs,
            isPlaylist = true,
            route = "playlist",
        )
    }

    fun looksLikePlaylist(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("list=") ||
            lower.contains("/playlist") ||
            lower.contains("/sets/") ||
            Regex("""[?&]index=\d+""").containsMatchIn(lower)
    }

    private fun analyzeWithYoutubeDl(url: String): AnalysisResult {
        (context.applicationContext as ClipFlowApplication).ensureEngine()
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--no-cache-dir")
            cookieStore.activeFile()?.let { addOption("--cookies", it.absolutePath) }
            applySiteOptions(url)
        }
        val response = YoutubeDL.getInstance().execute(request)
        val root = JSONObject(response.out.trim())
        if (root.optString("_type") == "playlist" || root.optJSONArray("entries") != null) {
            return analyzePlaylistShell(url)
        }
        return parseVideoJson(url, root, route = siteRouter.routeName(url).ifBlank { "ytdlp" })
    }

    private fun applySiteOptions(url: String) {
        // Keep extractor choices stable for KR platforms when yt-dlp handles them.
        when {
            SiteRouter.isSoop(url) -> {
                // no-op reserved for future extractor args
            }
            SiteRouter.isCime(url) -> {
                // no-op reserved for future extractor args
            }
        }
    }

    private fun parseVideoJson(url: String, root: JSONObject, route: String): AnalysisResult {
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
                            id = "$formatId-$index-${hash(url).take(6)}",
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
                            route = route,
                        ),
                    )
                }
            }
        }.ifEmpty {
            val mediaUrl = root.optString("url")
            if (mediaUrl.isBlank()) emptyList() else listOf(
                MediaCandidate(
                    id = root.optString("format_id", "best") + "-" + hash(url).take(6),
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
                    route = route,
                ),
            )
        }
        if (candidates.isEmpty()) error("다운로드 가능한 영상 형식을 찾지 못했습니다.")
        return AnalysisResult(title, candidates, route = route)
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
            id = "direct-mp4-${hash(url).take(8)}",
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
            route = "direct",
        )
    }

    private fun shouldTryBrowserFallback(url: String, message: String): Boolean {
        // Desktop 공용 폴백과 같은 취지: yt-dlp generic/차단/미지원이면 브라우저 네트워크 캡처.
        val lower = message.lowercase()
        if (SiteRouter.isChzzkClip(url) || SiteRouter.isChzzkVideo(url)) return false
        if (looksLikePlaylist(url)) return false
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        if (host.contains("anilife")) return true
        return lower.contains("unsupported url") ||
            lower.contains("no video formats") ||
            lower.contains("unable to extract") ||
            lower.contains("unable to download webpage") ||
            lower.contains("ssl") ||
            lower.contains("eof") ||
            lower.contains("not find") ||
            lower.contains("http error 403") ||
            lower.contains("sign in") ||
            lower.contains("generic") ||
            lower.contains("tls")
    }

    private fun hash(value: String): String = value.hashCode().toUInt().toString(16)

    companion object {
        // Prefer desktop Chrome UA for yt-dlp/generic hosts (same family as browser fallback).
        const val BROWSER_USER_AGENT = BrowserMediaFallback.DESKTOP_CHROME_UA
    }
}
