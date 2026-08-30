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
import java.net.InetAddress
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

        val ytdlpError = runCatching { analyzeWithTransportRetry(url) }
            .fold(onSuccess = { return it }, onFailure = { it })

        if (allowBrowserFallback && shouldTryBrowserFallback(url, ytdlpError.message.orEmpty())) {
            return runCatching {
                analyzeWithHiddenBrowser(url, useProxyFirst = usedTlsSplitRetry(ytdlpError))
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
                        if (TlsFragmentingProxy.isAppUsingVpn(context) &&
                            TlsFragmentingProxy.isConnectionTermination(browserError)
                        ) {
                            append("\n활성 VPN이 ClipFlow 연결을 가로채고 있습니다. ")
                            append("VPN 앱의 앱 관리에서 ClipFlow의 DPI/검열 우회를 켜거나 ")
                            append("ClipFlow를 VPN 제외 앱으로 설정하세요.")
                        }
                    },
                )
            }
        }
        throw ytdlpError
    }

    private fun analyzeWithHiddenBrowser(url: String, useProxyFirst: Boolean): AnalysisResult {
        val browser = BrowserMediaFallback(context.applicationContext, cookieStore)
        if (useProxyFirst) {
            return browser.capture(url, proxyUrl = TlsFragmentingProxy.endpoint(context))
        }
        return try {
            browser.capture(url)
        } catch (initial: Throwable) {
            if (!TlsFragmentingProxy.isConnectionTermination(initial)) throw initial
            try {
                browser.capture(url, proxyUrl = TlsFragmentingProxy.endpoint(context))
            } catch (retry: Throwable) {
                retry.addSuppressed(initial)
                throw retry
            }
        }
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

    fun analyzePlaylistShell(url: String): AnalysisResult = withTlsSplitRetry { proxyUrl ->
        analyzePlaylistShell(url, proxyUrl)
    }

    private fun analyzePlaylistShell(url: String, proxyUrl: String?): AnalysisResult {
        (context.applicationContext as ClipFlowApplication).ensureEngine()
        val request = YoutubeDLRequest(url).apply {
            addOption("--flat-playlist")
            addOption("--dump-single-json")
            addOption("--no-warnings")
            addOption("--no-cache-dir")
            cookieStore.ytDlpCookieFileFor(url)?.let { addOption("--cookies", it.absolutePath) }
            addOption("--user-agent", BROWSER_USER_AGENT)
            proxyUrl?.let { addOption("--proxy", it) }
            applySiteOptions(url)
        }
        val response = YoutubeDL.getInstance().execute(request)
        val root = JSONObject(response.out.trim())
        val type = root.optString("_type")
        val entries = root.optJSONArray("entries")
        if (type != "playlist" && entries == null) {
            return analyze(url)
        }
        val title = root.cleanString("title").ifBlank { "재생목록" }
        val playlistId = root.cleanString("id").ifBlank { hash(url) }
        val parentId = "playlist-$playlistId"
        val entryRefs = buildList {
            if (entries != null) {
                for (index in 0 until entries.length()) {
                    val item = entries.optJSONObject(index) ?: continue
                    val entryUrl = item.cleanString("url")
                        .ifBlank { item.cleanString("webpage_url") }
                        .ifBlank {
                            val id = item.cleanString("id")
                            if (id.isNotBlank() && url.contains("youtube", true)) {
                                "https://www.youtube.com/watch?v=$id"
                            } else ""
                        }
                    add(
                        PlaylistEntryRef(
                            index = index,
                            url = entryUrl,
                            title = item.cleanString("title").ifBlank { "항목 ${index + 1}" },
                            thumbnailUrl = item.cleanString("thumbnail")
                                .ifBlank { item.optJSONArray("thumbnails")?.optJSONObject(0)?.cleanString("url").orEmpty() },
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
            uploader = root.cleanString("uploader"),
            thumbnailUrl = root.cleanString("thumbnail")
                .ifBlank { entryRefs.firstNotNullOfOrNull { it.thumbnailUrl.takeIf(String::isNotBlank) }.orEmpty() },
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

    private fun analyzeWithTransportRetry(url: String): AnalysisResult {
        return try {
            analyzeWithYoutubeDl(url, proxyUrl = null)
        } catch (initial: Throwable) {
            if (!TlsFragmentingProxy.isConnectionTermination(initial)) throw initial
            val ipError = try {
                return analyzeWithIpHostFallback(url)
            } catch (error: Throwable) {
                error
            }
            try {
                analyzeWithYoutubeDl(url, TlsFragmentingProxy.endpoint(context))
            } catch (proxyError: Throwable) {
                proxyError.addSuppressed(initial)
                proxyError.addSuppressed(ipError)
                throw proxyError
            }
        }
    }

    private fun analyzeWithIpHostFallback(url: String): AnalysisResult {
        val original = URI(url)
        require(original.scheme.equals("https", true) && !original.host.isNullOrBlank()) {
            "IP 우회 재시도를 지원하지 않는 URL입니다."
        }
        val host = original.host
        val hostHeader = if (original.port > 0 && original.port != 443) "$host:${original.port}" else host
        val addresses = InetAddress.getAllByName(host)
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .distinct()
        require(addresses.isNotEmpty()) { "호스트 IP를 찾지 못했습니다: $host" }

        var lastError: Throwable? = null
        for (address in addresses) {
            val inetAddress = InetAddress.getByName(address)
            val ipUrl = URI(
                original.scheme,
                original.userInfo,
                address,
                original.port,
                original.path,
                original.query,
                original.fragment,
            ).toString()
            try {
                HttpsHostRouting.verifyDefaultCertificate(
                    address = inetAddress,
                    port = original.port.takeIf { it > 0 } ?: 443,
                    expectedHost = host,
                )
                val request = YoutubeDLRequest(ipUrl).apply {
                    addOption("--dump-single-json")
                    addOption("--no-playlist")
                    addOption("--no-warnings")
                    addOption("--no-cache-dir")
                    addOption("--force-generic-extractor")
                    addOption("--no-check-certificates")
                    addOption("--user-agent", BROWSER_USER_AGENT)
                    addOption("--referer", url)
                    addOption("--add-header", "Host:$hostHeader")
                    cookieStore.cookieHeaderFor(url).takeIf { it.isNotBlank() }?.let {
                        addOption("--add-header", "Cookie:$it")
                    }
                }
                val response = YoutubeDL.getInstance().execute(request)
                val root = JSONObject(response.out.trim())
                return parseIpHostJson(url, root)
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("IP 우회 분석에 실패했습니다.")
    }

    private fun analyzeWithYoutubeDl(url: String, proxyUrl: String?): AnalysisResult {
        (context.applicationContext as ClipFlowApplication).ensureEngine()
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--no-cache-dir")
            cookieStore.ytDlpCookieFileFor(url)?.let { addOption("--cookies", it.absolutePath) }
            addOption("--user-agent", BROWSER_USER_AGENT)
            proxyUrl?.let { addOption("--proxy", it) }
            applySiteOptions(url)
        }
        val response = YoutubeDL.getInstance().execute(request)
        val root = JSONObject(response.out.trim())
        if (root.optString("_type") == "playlist" || root.optJSONArray("entries") != null) {
            return analyzePlaylistShell(url, proxyUrl)
        }
        return parseVideoJson(url, root, route = siteRouter.routeName(url).ifBlank { "ytdlp" })
    }

    private fun <T> withTlsSplitRetry(block: (String?) -> T): T {
        return try {
            block(null)
        } catch (initial: Throwable) {
            if (!TlsFragmentingProxy.isConnectionTermination(initial)) throw initial
            try {
                block(TlsFragmentingProxy.endpoint(context))
            } catch (retry: Throwable) {
                retry.addSuppressed(initial)
                throw retry
            }
        }
    }

    private fun usedTlsSplitRetry(error: Throwable): Boolean =
        error.suppressed.any(TlsFragmentingProxy::isConnectionTermination)

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
        // Never promote audio-only streams (e.g. YouTube itag 139 ~0.7MB) as the card.
        // yt-dlp resolves format_id from the page URL even when format "url" is blank.
        val candidates = buildList {
            if (formats != null) {
                for (index in 0 until formats.length()) {
                    val item = formats.optJSONObject(index) ?: continue
                    val formatId = item.optString("format_id")
                    val videoCodec = item.optString("vcodec")
                    val extension = item.optString("ext")
                    if (formatId.isBlank()) continue
                    if (videoCodec.isBlank() || videoCodec.equals("none", true)) continue
                    if (extension in setOf("mhtml", "html", "m4a", "webm") &&
                        item.optString("acodec").isNotBlank() &&
                        item.optInt("height", 0) <= 0 &&
                        item.optInt("width", 0) <= 0
                    ) {
                        continue
                    }
                    val protocol = item.optString("protocol")
                    val manifestUrl = item.optString("manifest_url")
                    val mediaUrl = item.optString("url")
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
            // Page-level fallback: only if the selected format actually has video.
            val vcodec = root.optString("vcodec")
            val height = root.optInt("height", 0)
            val hasVideo = height > 0 && vcodec.isNotBlank() && !vcodec.equals("none", true)
            if (!hasVideo) {
                // Let yt-dlp pick best video+audio at download time from the page URL.
                listOf(
                    MediaCandidate(
                        id = "best-${hash(url).take(6)}",
                        sourceUrl = url,
                        mediaUrl = "",
                        title = title,
                        uploader = uploader,
                        thumbnailUrl = thumbnail,
                        formatId = "best",
                        extension = "mp4",
                        width = root.optInt("width", 0),
                        height = height,
                        fps = root.optDouble("fps", 0.0).toInt(),
                        videoCodec = "unknown",
                        audioCodec = "unknown",
                        dynamicRange = "",
                        sizeBytes = 0L,
                        durationSeconds = duration,
                        isManifest = false,
                        route = route,
                    ),
                )
            } else {
                listOf(
                    MediaCandidate(
                        id = root.optString("format_id", "best") + "-" + hash(url).take(6),
                        sourceUrl = url,
                        mediaUrl = "", // never bind expired googlevideo direct for ytdlp page downloads
                        title = title,
                        uploader = uploader,
                        thumbnailUrl = thumbnail,
                        formatId = root.optString("format_id", "best"),
                        extension = root.optString("ext", "mp4"),
                        width = root.optInt("width", 0),
                        height = height,
                        fps = root.optDouble("fps", 0.0).toInt(),
                        videoCodec = vcodec,
                        audioCodec = root.optString("acodec"),
                        dynamicRange = root.optString("dynamic_range"),
                        sizeBytes = root.optLong("filesize", 0L),
                        durationSeconds = duration,
                        isManifest = false,
                        route = route,
                    ),
                )
            }
        }
        if (candidates.isEmpty()) error("다운로드 가능한 영상 형식을 찾지 못했습니다.")
        return AnalysisResult(title, candidates, route = route)
    }

    private fun parseIpHostJson(sourceUrl: String, root: JSONObject): AnalysisResult {
        val mediaUrl = root.cleanString("url")
        val extension = root.cleanString("ext").ifBlank { "mp4" }
        val videoExtension = root.cleanString("video_ext")
        require(
            mediaUrl.startsWith("http") &&
                (videoExtension.isBlank() || videoExtension != "none") &&
                extension.lowercase() in setOf("mp4", "webm", "mkv", "mov", "m3u8", "mpd"),
        ) { "IP 우회 응답에서 직접 재생 가능한 영상 URL을 찾지 못했습니다." }
        val title = root.cleanString("title").ifBlank { "제목 없는 영상" }
        val isManifest = extension.equals("m3u8", true) || extension.equals("mpd", true) ||
            mediaUrl.contains(".m3u8", true) || mediaUrl.contains(".mpd", true)
        val duration = root.optDouble("duration", 0.0).toInt().takeIf { it > 0 }
            ?: probePageDuration(sourceUrl)
        val candidate = MediaCandidate(
            id = "direct-${hash(sourceUrl).take(8)}",
            sourceUrl = sourceUrl,
            mediaUrl = mediaUrl,
            title = title,
            uploader = root.cleanString("uploader"),
            thumbnailUrl = root.cleanString("thumbnail"),
            formatId = "direct",
            extension = extension,
            width = root.optInt("width", 0),
            height = root.optInt("height", 0).takeIf { it > 0 } ?: heightFromMediaUrl(mediaUrl),
            fps = root.optDouble("fps", 0.0).toInt(),
            videoCodec = root.cleanString("vcodec").ifBlank { "unknown" },
            audioCodec = root.cleanString("acodec").ifBlank { "unknown" },
            dynamicRange = root.cleanString("dynamic_range"),
            sizeBytes = root.optLong("filesize", 0L).takeIf { it > 0 }
                ?: root.optLong("filesize_approx", 0L).takeIf { it > 0 }
                ?: probeRemoteSize(mediaUrl, sourceUrl),
            durationSeconds = duration,
            isManifest = isManifest,
            route = "direct",
        )
        return AnalysisResult(title, listOf(candidate), route = "direct")
    }

    private fun probePageDuration(sourceUrl: String): Int = runCatching {
        if (!sourceUrl.startsWith("https://", ignoreCase = true)) return@runCatching 0
        val connection = HttpsHostRouting.connect(sourceUrl) {
            it.requestMethod = "GET"
            it.connectTimeout = 10_000
            it.readTimeout = 10_000
            it.setRequestProperty("User-Agent", BROWSER_USER_AGENT)
            it.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            it.setRequestProperty("Accept-Encoding", "identity")
            cookieStore.cookieHeaderFor(sourceUrl).takeIf { header -> header.isNotBlank() }?.let { header ->
                it.setRequestProperty("Cookie", header)
            }
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching 0
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(16 * 1024)
                while (result.length < MAX_PAGE_METADATA_CHARS) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, MAX_PAGE_METADATA_CHARS - result.length))
                    if (count < 0) break
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
            durationSecondsFromPageHtml(html)
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(0)

    private fun probeRemoteSize(mediaUrl: String, referer: String): Long = runCatching {
        fun configure(connection: HttpURLConnection) {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Range", "bytes=0-0")
            connection.setRequestProperty("User-Agent", BROWSER_USER_AGENT)
            connection.setRequestProperty("Referer", referer)
            cookieStore.cookieHeaderFor(mediaUrl).takeIf { it.isNotBlank() }?.let {
                connection.setRequestProperty("Cookie", it)
            }
        }
        var regular: HttpURLConnection? = null
        val connection = try {
            (URL(mediaUrl).openConnection() as HttpURLConnection).also {
                regular = it
                configure(it)
                it.connect()
                it.responseCode
            }
        } catch (initial: Throwable) {
            regular?.disconnect()
            if (!TlsFragmentingProxy.isConnectionTermination(initial) || !mediaUrl.startsWith("https://")) {
                throw initial
            }
            HttpsHostRouting.connect(mediaUrl) { configure(it) }
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) return@runCatching 0L
            connection.getHeaderField("Content-Range")
                ?.substringAfterLast('/', "")
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: connection.contentLengthLong.coerceAtLeast(0L)
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(0L)

    private fun heightFromMediaUrl(mediaUrl: String): Int =
        Regex("""(?:^|[/_])(\d{3,4})[pP](?:[/_.]|$)""")
            .find(mediaUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0

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
        private const val MAX_PAGE_METADATA_CHARS = 3 * 1024 * 1024
    }
}

internal fun durationSecondsFromPageHtml(html: String): Int {
    val metaDuration = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
        .findAll(html)
        .firstNotNullOfOrNull { match ->
            val tag = match.value
            val property = htmlAttribute(tag, "property").ifBlank { htmlAttribute(tag, "name") }
            if (!property.equals("video:duration", ignoreCase = true)) return@firstNotNullOfOrNull null
            htmlAttribute(tag, "content").toIntOrNull()?.takeIf { it > 0 }
        }
    if (metaDuration != null) return metaDuration

    val isoDuration = Regex(
        """[\"']duration[\"']\s*:\s*[\"']PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?[\"']""",
        RegexOption.IGNORE_CASE,
    ).find(html)
    if (isoDuration != null) {
        val hours = isoDuration.groupValues[1].toIntOrNull() ?: 0
        val minutes = isoDuration.groupValues[2].toIntOrNull() ?: 0
        val seconds = isoDuration.groupValues[3].toIntOrNull() ?: 0
        val total = hours * 3600 + minutes * 60 + seconds
        if (total > 0) return total
    }

    return Regex("""[\"']video_duration[\"']\s*:\s*[\"']?(\d+)[\"']?""", RegexOption.IGNORE_CASE)
        .findAll(html)
        .mapNotNull { it.groupValues[1].toIntOrNull() }
        .firstOrNull { it > 0 }
        ?: 0
}

private fun htmlAttribute(tag: String, name: String): String = Regex(
    """\b${Regex.escape(name)}\s*=\s*([\"'])(.*?)\1""",
    RegexOption.IGNORE_CASE,
).find(tag)?.groupValues?.getOrNull(2).orEmpty()

private fun JSONObject.cleanString(key: String): String = optString(key)
    .trim()
    .takeUnless { it.equals("null", ignoreCase = true) }
    .orEmpty()
