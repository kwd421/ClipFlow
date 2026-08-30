package app.clipflow.android.data

import app.clipflow.android.model.MediaCandidate
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.regex.Pattern

/**
 * Desktop-parity site routes for Chzzk / SOOP / CIME.
 * Uses public JSON endpoints when possible, then falls back to yt-dlp.
 */
class SiteRouter(private val cookieStore: CookieFileStore) {
    fun analyzeIfKnown(url: String): AnalysisResult? {
        return when {
            isChzzkClip(url) -> analyzeChzzkClip(url)
            isChzzkVideo(url) -> analyzeChzzkVideo(url)
            isSoop(url) -> analyzeSoop(url)
            isCime(url) -> analyzeCime(url)
            else -> null
        }
    }

    fun routeName(url: String): String = when {
        isChzzkClip(url) || isChzzkVideo(url) -> "chzzk"
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
        fun isChzzkClip(url: String) = CHZZK_CLIP.matcher(url).find()
        fun isChzzkVideo(url: String) = CHZZK_VIDEO.matcher(url).find()
        fun isSoop(url: String) = SOOP.matcher(url).find()
        fun isCime(url: String): Boolean {
            if (!CIME.matcher(url).find()) return false
            val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
            return path.contains("vod", true) || path.contains("clip", true) || path.contains("/v/", true)
        }
    }
}
