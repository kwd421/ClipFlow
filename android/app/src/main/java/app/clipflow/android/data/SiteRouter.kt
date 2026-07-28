package app.clipflow.android.data

import app.clipflow.android.model.MediaCandidate
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.regex.Pattern

/**
 * Desktop-parity site routes for Chzzk / SOOP / CIME / AniLife.
 * Uses public JSON endpoints when possible, then falls back to yt-dlp.
 */
class SiteRouter(private val cookieStore: CookieFileStore) {
    fun analyzeIfKnown(url: String): AnalysisResult? {
        return when {
            isChzzkClip(url) -> analyzeChzzkClip(url)
            isChzzkVideo(url) -> analyzeChzzkVideo(url)
            isAniLife(url) -> analyzeAniLife(url)
            isSoop(url) -> analyzeSoop(url)
            isCime(url) -> analyzeCime(url)
            else -> null
        }
    }

    fun routeName(url: String): String = when {
        isChzzkClip(url) || isChzzkVideo(url) -> "chzzk"
        isAniLife(url) -> "anilife"
        isSoop(url) -> "soop"
        isCime(url) -> "cime"
        else -> ""
    }

    private fun analyzeChzzkClip(url: String): AnalysisResult {
        val clipUid = CHZZK_CLIP.matcher(url).let { if (it.find()) it.group(1) else error("invalid chzzk clip") }
        val cookie = cookieStore.cookieHeaderFor(url)
        val detail = getJson(
            "https://api.chzzk.naver.com/service/v1/clips/$clipUid/detail",
            mapOf(
                "Referer" to "https://chzzk.naver.com/clips/$clipUid",
                "Origin" to "https://chzzk.naver.com",
            ),
            cookie,
        )
        val content = detail.optJSONObject("content") ?: JSONObject()
        if (content.optBoolean("blindType") || content.optString("adult") == "NEED_LOGIN") {
            if (cookie.isBlank() && content.optString("adult").isNotBlank()) {
                error("CHZZK 성인 콘텐츠는 cookies.txt 로그인이 필요합니다.")
            }
        }
        val videoId = content.optString("videoId")
        if (videoId.isBlank()) error("CHZZK 클립 videoId를 찾지 못했습니다.")
        val card = postJson(
            "https://apis.naver.com/rmcnmv/rmc/v2/shortform/list",
            JSONObject()
                .put("videoId", videoId)
                .put("key", content.optString("recId"))
                .put("panelType", "sdk_chzzk")
                .put("referer", "https://chzzk.naver.com/clips/$clipUid")
                .toString(),
            mapOf(
                "Referer" to "https://chzzk.naver.com/clips/$clipUid",
                "Origin" to "https://chzzk.naver.com",
                "Content-Type" to "application/json",
            ),
            cookie,
        )
        val title = content.optString("clipTitle").ifBlank { content.optString("title") }
            .ifBlank { "CHZZK clip $clipUid" }
        val duration = content.optDouble("duration", 0.0).toInt()
            .takeIf { it > 0 }
            ?: content.optInt("durationSeconds", 0)
        val thumbnail = content.optString("thumbnailImageUrl")
            .ifBlank { content.optString("clipImageUrl") }
        val media = extractMediaUrls(card) + extractMediaUrls(content)
        val candidates = mediaToCandidates(media, "https://chzzk.naver.com/clips/$clipUid", title, thumbnail, duration, "chzzk")
        if (candidates.isEmpty()) error("CHZZK 클립 미디어 URL을 찾지 못했습니다.")
        return AnalysisResult(title, candidates)
    }

    private fun analyzeChzzkVideo(url: String): AnalysisResult {
        val videoNo = CHZZK_VIDEO.matcher(url).let { if (it.find()) it.group(1) else error("invalid chzzk video") }
        val cookie = cookieStore.cookieHeaderFor(url)
        val detail = getJson(
            "https://api.chzzk.naver.com/service/v3/videos/$videoNo",
            mapOf(
                "Referer" to "https://chzzk.naver.com/video/$videoNo",
                "Origin" to "https://chzzk.naver.com",
            ),
            cookie,
        )
        val content = detail.optJSONObject("content") ?: JSONObject()
        val title = content.optString("videoTitle").ifBlank { content.optString("title") }
            .ifBlank { "CHZZK video $videoNo" }
        val duration = content.optDouble("duration", 0.0).toInt()
        val thumbnail = content.optString("thumbnailImageUrl").ifBlank { content.optString("imageUrl") }
        val playback = when {
            content.optString("videoId").isNotBlank() && content.optString("inKey").isNotBlank() -> {
                getJson(
                    "https://apis.naver.com/neonplayer/vodplay/v2/playback/${content.optString("videoId")}?key=${content.optString("inKey")}",
                    mapOf(
                        "Referer" to "https://chzzk.naver.com/video/$videoNo",
                        "Origin" to "https://chzzk.naver.com",
                    ),
                    cookie,
                )
            }
            content.optString("liveRewindPlaybackJson").isNotBlank() ->
                JSONObject(content.optString("liveRewindPlaybackJson"))
            else -> null
        } ?: error("CHZZK 재생 정보를 찾지 못했습니다.")
        val media = extractMediaUrls(playback)
        val candidates = mediaToCandidates(
            media,
            "https://chzzk.naver.com/video/$videoNo",
            title,
            thumbnail,
            duration,
            "chzzk",
        )
        if (candidates.isEmpty()) error("CHZZK 동영상 미디어 URL을 찾지 못했습니다.")
        return AnalysisResult(title, candidates)
    }

    private fun analyzeSoop(url: String): AnalysisResult? {
        // Prefer yt-dlp for full SOOP coverage; surface a soft site label only.
        // Dedicated catch-list expansion is handled by playlist path when yt-dlp returns entries.
        return null
    }

    private fun analyzeCime(url: String): AnalysisResult? {
        // CIME HTML embeds vary; use yt-dlp + browser fallback.
        return null
    }

    /**
     * AniLife watch pages do not work in Android WebView (TLS/CF close).
     * Native path: api.anilife.app media envelope → decrypt → gcdn master.m3u8.
     */
    private fun analyzeAniLife(url: String): AnalysisResult {
        val mediaId = ANILIFE_WATCH.matcher(url).let {
            if (it.find()) it.group(1) else error("AniLife 영상 ID를 찾지 못했습니다.")
        }
        val pagePath = "/watch?id=$mediaId"
        val cookie = cookieStore.cookieHeaderFor("https://anilife.app/")
        val body = getRaw(
            "https://api.anilife.app/v1/media/$mediaId",
            mapOf(
                "Accept" to "application/json, text/plain, */*",
                "Referer" to "https://anilife.app/",
                "Origin" to "https://anilife.app",
                "x-client-id" to "web",
                "x-build-id" to ANILIFE_BUILD_ID,
                "x-anilife-referer" to java.net.URLEncoder.encode(pagePath, Charsets.UTF_8.name()),
                "x-device-token" to AniLifeCodec.sha256Hex(mediaId).take(32),
            ),
            cookie,
        )
        val payload = AniLifeCodec.decryptMediaBody(body)
        val episode = payload.optJSONObject("episode") ?: JSONObject()
        val media = payload.optJSONObject("media") ?: JSONObject()
        val access = payload.optString("access")
        if (access.isBlank()) error("AniLife 스트림 토큰(access)을 찾지 못했습니다.")

        val titleBase = media.optJSONObject("name")?.optString("kr")
            ?.ifBlank { media.optJSONObject("name")?.optString("en").orEmpty() }
            .orEmpty()
            .ifBlank { media.optString("title") }
            .ifBlank { "AniLife $mediaId" }
        val epNum = episode.optString("episode_num").ifBlank { episode.opt("episode_num")?.toString().orEmpty() }
        val subject = episode.optString("subject")
        val title = buildString {
            append(titleBase)
            if (epNum.isNotBlank()) append(" - ").append(epNum).append("화")
            if (subject.isNotBlank()) append(" ").append(subject)
        }
        val thumbnail = episode.optString("thumbnail")
            .ifBlank { media.optString("image") }
        val duration = parseAniLifeDuration(episode.optString("duration"))
        val masterUrl = "https://api.gcdn.app/v1/manifest/a/$access/master.m3u8"
        val pageUrl = "https://anilife.app$pagePath"
        val gcdnHeaders = mapOf(
            "Referer" to "https://anilife.app/",
            "Origin" to "https://anilife.app",
            "Accept" to "*/*",
        )
        val masterBody = runCatching { getRaw(masterUrl, gcdnHeaders, cookie) }.getOrDefault("")
        val streamVariants = parseHlsMaster(masterBody)
        val candidates = if (streamVariants.isNotEmpty()) {
            streamVariants.mapIndexed { index, variant ->
                MediaCandidate(
                    id = "anilife-$mediaId-${variant.height}-$index",
                    sourceUrl = pageUrl,
                    mediaUrl = variant.url,
                    title = title,
                    uploader = "AniLife",
                    thumbnailUrl = thumbnail,
                    formatId = if (variant.height > 0) "anilife-${variant.height}" else "anilife-best",
                    extension = "m3u8",
                    width = variant.width,
                    height = variant.height,
                    fps = 0,
                    videoCodec = "h264",
                    audioCodec = "aac",
                    dynamicRange = "",
                    sizeBytes = if (variant.bandwidth > 0 && duration > 0) {
                        variant.bandwidth.toLong() * duration / 8
                    } else {
                        0L
                    },
                    durationSeconds = duration,
                    isManifest = true,
                    route = "anilife",
                )
            }
        } else {
            // Fallback: master only (yt-dlp picks a variant).
            listOf(
                MediaCandidate(
                    id = "anilife-$mediaId-master",
                    sourceUrl = pageUrl,
                    mediaUrl = masterUrl,
                    title = title,
                    uploader = "AniLife",
                    thumbnailUrl = thumbnail,
                    formatId = "anilife-best",
                    extension = "m3u8",
                    width = 0,
                    height = 0,
                    fps = 0,
                    videoCodec = "h264",
                    audioCodec = "aac",
                    dynamicRange = "",
                    sizeBytes = 0L,
                    durationSeconds = duration,
                    isManifest = true,
                    route = "anilife",
                ),
            )
        }
        if (candidates.isEmpty()) error("AniLife 미디어 후보를 만들지 못했습니다.")
        return AnalysisResult(title, candidates, route = "anilife")
    }

    private data class HlsVariant(
        val url: String,
        val width: Int,
        val height: Int,
        val bandwidth: Int,
    )

    private fun parseHlsMaster(body: String): List<HlsVariant> {
        if (body.isBlank()) return emptyList()
        val out = ArrayList<HlsVariant>()
        val lines = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                val meta = line.substringAfter(':')
                val bandwidth = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
                    .find(meta)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val res = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
                    .find(meta)
                val width = res?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val height = res?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
                val next = lines.getOrNull(i + 1).orEmpty()
                if (next.startsWith("http", ignoreCase = true)) {
                    out += HlsVariant(url = next, width = width, height = height, bandwidth = bandwidth)
                    i += 2
                    continue
                }
            }
            i += 1
        }
        return out.sortedByDescending { it.height.takeIf { h -> h > 0 } ?: it.bandwidth }
    }

    private fun parseAniLifeDuration(raw: String): Int {
        val text = raw.trim()
        if (text.isEmpty()) return 0
        // "24분", "1시간 30분", "90"
        val hour = Regex("""(\d+)\s*시간""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val min = Regex("""(\d+)\s*분""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: text.toIntOrNull()
            ?: 0
        val sec = Regex("""(\d+)\s*초""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return hour * 3600 + min * 60 + sec
    }

    private fun getRaw(url: String, headers: Map<String, String>, cookie: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", YoutubeDlAnalyzer.BROWSER_USER_AGENT)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            // Response is LZ-UTF16 text (content-type often text/html); read as UTF-8 string of code units.
            val bytes = stream?.readBytes() ?: ByteArray(0)
            val body = bytes.toString(Charsets.UTF_8)
            if (code !in 200..299) error("HTTP $code: ${body.take(200)}")
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun mediaToCandidates(
        media: List<MediaItem>,
        sourceUrl: String,
        title: String,
        thumbnail: String,
        duration: Int,
        route: String,
    ): List<MediaCandidate> {
        return media.mapIndexed { index, item ->
            val isMp4 = item.url.contains(".mp4", ignoreCase = true)
            MediaCandidate(
                id = "$route-$index-${item.height}",
                sourceUrl = sourceUrl,
                mediaUrl = item.url,
                title = title,
                uploader = route.uppercase(),
                thumbnailUrl = thumbnail,
                formatId = "$route-${item.height.coerceAtLeast(index + 1)}",
                extension = if (isMp4) "mp4" else "m3u8",
                width = item.width,
                height = item.height,
                fps = item.fps,
                videoCodec = if (isMp4) "unknown" else "h264",
                audioCodec = "unknown",
                dynamicRange = "",
                sizeBytes = if (item.bandwidth > 0 && duration > 0) item.bandwidth.toLong() * duration / 8 else 0L,
                durationSeconds = duration,
                isManifest = !isMp4,
                route = route,
            )
        }.sortedByDescending { it.height }
    }

    private data class MediaItem(
        val url: String,
        val width: Int = 0,
        val height: Int = 0,
        val bandwidth: Int = 0,
        val fps: Int = 0,
    )

    private fun extractMediaUrls(payload: Any?): List<MediaItem> {
        val found = linkedMapOf<String, MediaItem>()
        fun add(url: String, width: Int, height: Int, bandwidth: Int, fps: Int) {
            val clean = url.trim()
            if (!clean.startsWith("http")) return
            val lower = clean.lowercase()
            if (".mp4" !in lower && ".m3u8" !in lower) return
            val previous = found[clean]
            if (previous == null || height >= previous.height) {
                found[clean] = MediaItem(clean, width, height, bandwidth, fps)
            }
        }
        fun walk(value: Any?, width: Int = 0, height: Int = 0, bandwidth: Int = 0, fps: Int = 0) {
            when (value) {
                is JSONObject -> {
                    val w = value.optInt("@width", value.optInt("width", width))
                    val h = value.optInt("@height", value.optInt("height", height))
                    val b = value.optInt("@bandwidth", value.optInt("bandwidth", bandwidth))
                    val f = value.optDouble("@frameRate", value.optDouble("fps", fps.toDouble())).toInt()
                    listOf("url", "value", "path", "source", "BaseURL", "baseURL", "@nvod:m3u").forEach { key ->
                        when (val child = value.opt(key)) {
                            is String -> add(child, w, h, b, f)
                            is JSONArray -> for (i in 0 until child.length()) {
                                when (val item = child.opt(i)) {
                                    is String -> add(item, w, h, b, f)
                                    else -> walk(item, w, h, b, f)
                                }
                            }
                            else -> walk(child, w, h, b, f)
                        }
                    }
                    val other = value.optJSONObject("otherAttributes")
                    if (other != null) {
                        add(other.optString("m3u"), w, h, b, f)
                        add(other.optString("m3u8"), w, h, b, f)
                    }
                    value.keys().forEach { key ->
                        if (key !in setOf("url", "value", "path", "source", "BaseURL", "baseURL")) {
                            walk(value.opt(key), w, h, b, f)
                        }
                    }
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i), width, height, bandwidth, fps)
                is String -> add(value, width, height, bandwidth, fps)
            }
        }
        walk(payload)
        return found.values.sortedByDescending { it.height }
    }

    private fun getJson(url: String, headers: Map<String, String>, cookie: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", YoutubeDlAnalyzer.BROWSER_USER_AGENT)
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code: ${body.take(200)}")
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(url: String, body: String, headers: Map<String, String>, cookie: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 12_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", YoutubeDlAnalyzer.BROWSER_USER_AGENT)
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code: ${response.take(200)}")
            JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private val CHZZK_CLIP = Pattern.compile("https?://chzzk\\.naver\\.com/clips/([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE)
        private val CHZZK_VIDEO = Pattern.compile("https?://chzzk\\.naver\\.com/video/(\\d+)", Pattern.CASE_INSENSITIVE)
        private val SOOP = Pattern.compile("https?://(?:[\\w-]+\\.)?(?:sooplive|afreecatv)\\.com/player/\\d+", Pattern.CASE_INSENSITIVE)
        private val CIME = Pattern.compile("https?://(?:www\\.)?ci\\.me/", Pattern.CASE_INSENSITIVE)
        private val ANILIFE_WATCH = Pattern.compile(
            "https?://(?:www\\.)?anilife\\.app/watch\\?(?:[^#]*&)?id=([0-9a-fA-F-]{36})",
            Pattern.CASE_INSENSITIVE,
        )
        // From Nuxt public.buildVersion; required by api.anilife.app client gate.
        private const val ANILIFE_BUILD_ID = "a731ecce-1a3a-413d-ad56-e03461d1f951"

        fun isChzzkClip(url: String) = CHZZK_CLIP.matcher(url).find()
        fun isChzzkVideo(url: String) = CHZZK_VIDEO.matcher(url).find()
        fun isAniLife(url: String) = ANILIFE_WATCH.matcher(url).find()
        fun isSoop(url: String) = SOOP.matcher(url).find()
        fun isCime(url: String): Boolean {
            if (!CIME.matcher(url).find()) return false
            val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
            return path.contains("vod", true) || path.contains("clip", true) || path.contains("/v/", true)
        }
    }
}
