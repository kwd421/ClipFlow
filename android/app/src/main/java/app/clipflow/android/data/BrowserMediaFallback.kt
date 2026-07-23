package app.clipflow.android.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import app.clipflow.android.model.MediaCandidate
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Desktop "공용 브라우저 폴백" counterpart.
 *
 * Desktop launches real Chrome with --log-net-log and parses DOM + media requests.
 * Android uses WebView network interception + full-page HTML scrape for the same
 * media URL patterns (m3u8 / mp4 / videoUrl / get_media / …).
 */
class BrowserMediaFallback(
    private val context: Context,
    private val cookieStore: CookieFileStore,
) {
    fun capture(url: String, timeoutSeconds: Long = 45): AnalysisResult {
        val mediaUrls = linkedSetOf<String>()
        val titleHolder = AtomicReference("브라우저 캡처")
        val htmlHolder = AtomicReference("")
        val pageError = AtomicReference<String?>(null)
        val main = Handler(Looper.getMainLooper())
        val webViewReady = CountDownLatch(1)
        var webView: WebView? = null

        main.post {
            try {
                CookieManager.getInstance().setAcceptCookie(true)
                injectCookies(url)
                webView = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.loadsImagesAutomatically = false
                    settings.blockNetworkImage = true
                    settings.userAgentString = DESKTOP_CHROME_UA
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?,
                        ) {
                            if (failingUrl == url || failingUrl.isNullOrBlank()) {
                                pageError.compareAndSet(null, description ?: "page error $errorCode")
                            }
                        }

                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            titleHolder.set(view?.title?.takeIf { it.isNotBlank() } ?: titleHolder.get())
                            // Collect <video>/<source> + scrape full HTML for embedded media defs.
                            view?.evaluateJavascript(JS_COLLECT_MEDIA) { value ->
                                parseJsStringList(value).forEach { mediaUrls.add(it) }
                            }
                            view?.evaluateJavascript(
                                "(function(){return document.documentElement?document.documentElement.outerHTML:'';})();",
                            ) { value ->
                                val html = unescapeJsString(value)
                                if (html.length > htmlHolder.get().length) htmlHolder.set(html)
                                extractMediaUrlsFromHtml(html, url).forEach { mediaUrls.add(it) }
                            }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val requestUrl = request?.url?.toString().orEmpty()
                            if (looksLikeMedia(requestUrl) || looksLikeMediaApi(requestUrl)) {
                                mediaUrls.add(requestUrl)
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                    loadUrl(url)
                }
            } catch (error: Throwable) {
                pageError.compareAndSet(null, error.message ?: "WebView 초기화 실패")
            } finally {
                webViewReady.countDown()
            }
        }

        webViewReady.await(5, TimeUnit.SECONDS)

        val started = System.nanoTime()
        val deadline = started + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        var mediaSeenAt = 0L
        while (System.nanoTime() < deadline) {
            // Re-scrape HTML periodically once the document grows.
            if (htmlHolder.get().length < 500 || mediaUrls.none { looksLikeMedia(it) }) {
                main.post {
                    webView?.evaluateJavascript(
                        "(function(){return document.documentElement?document.documentElement.outerHTML:'';})();",
                    ) { value ->
                        val html = unescapeJsString(value)
                        if (html.length > htmlHolder.get().length) {
                            htmlHolder.set(html)
                            extractMediaUrlsFromHtml(html, url).forEach { mediaUrls.add(it) }
                        }
                    }
                    webView?.evaluateJavascript(JS_COLLECT_MEDIA) { value ->
                        parseJsStringList(value).forEach { mediaUrls.add(it) }
                    }
                }
            }
            val hasMedia = mediaUrls.any { looksLikeMedia(it) || looksLikeMediaApi(it) }
            if (hasMedia) {
                if (mediaSeenAt == 0L) mediaSeenAt = System.nanoTime()
                // Grace period so additional quality variants can appear (desktop does this).
                if (System.nanoTime() - mediaSeenAt >= TimeUnit.SECONDS.toNanos(2)) break
            }
            try {
                Thread.sleep(350)
            } catch (_: InterruptedException) {
                break
            }
        }

        val latch = CountDownLatch(1)
        main.post {
            try {
                webView?.stopLoading()
                webView?.destroy()
            } catch (_: Throwable) {
            } finally {
                webView = null
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)

        // Prefer concrete media over remote API URLs when both exist.
        val media = mediaUrls
            .asSequence()
            .map { html_unescape(it).replace("\\/", "/") }
            .filter { it.startsWith("http") }
            .filter { looksLikeMedia(it) || looksLikeMediaApi(it) }
            .distinct()
            .toList()
        val playable = media.filter(::looksLikeMedia).ifEmpty { media }
        if (playable.isEmpty()) {
            val pageHint = pageError.get()?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            val htmlLen = htmlHolder.get().length
            error("브라우저 폴백에서 재생 가능한 미디어를 찾지 못했습니다$pageHint (html=${htmlLen}B).")
        }

        val html = htmlHolder.get()
        val title = titleHolder.get()
            .takeIf { it.isNotBlank() && it != "브라우저 캡처" }
            ?: titleFromHtml(html)
            ?: "브라우저 캡처"
        val candidates = playable.mapIndexed { index, mediaUrl ->
            val height = heightFromUrl(mediaUrl)
            val isMp4 = mediaUrl.contains(".mp4", ignoreCase = true) && !mediaUrl.contains(".m3u8", true)
            val isManifest = mediaUrl.contains(".m3u8", true) ||
                mediaUrl.contains(".mpd", true) ||
                mediaUrl.contains("/media/hls", true)
            MediaCandidate(
                id = "browser-$index-$height-${mediaUrl.hashCode().toUInt().toString(16).take(6)}",
                sourceUrl = url,
                mediaUrl = mediaUrl,
                title = title,
                uploader = "",
                thumbnailUrl = "",
                formatId = "browser-$height",
                extension = when {
                    isMp4 -> "mp4"
                    mediaUrl.contains(".m3u8", true) || mediaUrl.contains("/media/hls", true) -> "m3u8"
                    mediaUrl.contains(".mpd", true) -> "mpd"
                    mediaUrl.contains(".webm", true) -> "webm"
                    else -> "mp4"
                },
                width = 0,
                height = height,
                fps = 0,
                videoCodec = "unknown",
                audioCodec = "unknown",
                dynamicRange = "",
                sizeBytes = 0,
                durationSeconds = durationFromHtml(html),
                isManifest = isManifest,
                route = "browser",
            )
        }.sortedByDescending { it.height }
        return AnalysisResult(title, candidates, route = "browser")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun injectCookies(url: String) {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        if (host.isBlank()) return
        val origins = listOf(
            "https://$host",
            "https://www.$host".takeIf { !host.startsWith("www.") },
            url,
        ).filterNotNull().distinct()
        val contents = cookieStore.activeFile()?.readText() ?: return
        val now = System.currentTimeMillis() / 1000
        parseNetscapeCookies(contents)
            .filter { it.expiresAt <= 0 || it.expiresAt > now }
            .filter { cookie ->
                val domain = cookie.domain.removePrefix(".").lowercase()
                host == domain || host.endsWith(".$domain") || domain.endsWith(host)
            }
            .forEach { cookie ->
                val domainAttr = cookie.domain.let { if (it.startsWith(".")) it else ".$it" }
                val parts = buildList {
                    add("${cookie.name}=${cookie.value}")
                    add("Domain=$domainAttr")
                    add("Path=${cookie.path.ifBlank { "/" }}")
                    if (cookie.secure) add("Secure")
                }
                val setCookie = parts.joinToString("; ")
                origins.forEach { origin -> manager.setCookie(origin, setCookie) }
            }
        manager.flush()
    }

    companion object {
        // Match desktop Chrome fallback UA — Cloudflare / anime CDNs often reject mobile tokens.
        const val DESKTOP_CHROME_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

        private const val JS_COLLECT_MEDIA = """
            (function(){
              var urls=[];
              function add(u){ if(u && typeof u==='string' && u.indexOf('http')===0) urls.push(u); }
              document.querySelectorAll('video,source,audio').forEach(function(el){
                add(el.src); add(el.currentSrc);
                if(el.getAttribute){ add(el.getAttribute('data-src')); add(el.getAttribute('data-url')); }
              });
              return urls.join('\n');
            })();
        """

        fun looksLikeMedia(url: String): Boolean {
            val lower = url.lowercase()
            if (!lower.startsWith("http")) return false
            if (listOf(".m3u8", ".mpd", ".mp4", ".m4v", ".mov", ".webm").any { lower.contains(it) }) return true
            // Desktop-compatible path heuristics used by analyze_browser_dom_media.
            return lower.contains("/media/hls") ||
                lower.contains("/media/mp4") ||
                lower.contains("/video/get_media") ||
                (lower.contains("playlist") && lower.contains("m3u8"))
        }

        fun looksLikeMediaApi(url: String): Boolean {
            val lower = url.lowercase()
            if (!lower.startsWith("http")) return false
            return lower.contains("videoUrl", ignoreCase = true) ||
                lower.contains("/api/") && (lower.contains("video") || lower.contains("stream") || lower.contains("play")) ||
                lower.contains("get_media") ||
                lower.contains("mediaDefinitions")
        }

        fun heightFromUrl(url: String): Int {
            val heights = listOf(144, 240, 270, 360, 480, 540, 576, 720, 1080, 1440, 2160, 4320)
            val fromP = Regex("""(?<!\d)(\d{3,4})p(?!\d)""", RegexOption.IGNORE_CASE)
                .findAll(url)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .filter { it in heights }
            val fromPath = url.split('/').mapNotNull { it.toIntOrNull() }.filter { it in heights }
            return (fromP + fromPath).maxOrNull() ?: 0
        }

        fun extractMediaUrlsFromHtml(html: String, baseUrl: String): List<String> {
            if (html.isBlank()) return emptyList()
            val text = html_unescape(html).replace("\\/", "/")
            val found = linkedSetOf<String>()
            val patterns = listOf(
                Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE),
                Regex("""https?://[^\s"'<>\\]+?\.mpd[^\s"'<>\\]*""", RegexOption.IGNORE_CASE),
                Regex("""https?://[^\s"'<>\\]+?\.mp4[^\s"'<>\\]*""", RegexOption.IGNORE_CASE),
                Regex("""https?://[^\s"'<>\\]+?/media/hls[^\s"'<>\\]*""", RegexOption.IGNORE_CASE),
                Regex("""https?://[^\s"'<>\\]+?/media/mp4[^\s"'<>\\]*""", RegexOption.IGNORE_CASE),
                Regex("""https?://[^\s"'<>\\]+?get_media[^\s"'<>\\]*""", RegexOption.IGNORE_CASE),
                Regex(""""videoUrl"\s*:\s*"(https?://[^"]+)"""", RegexOption.IGNORE_CASE),
                Regex(""""file"\s*:\s*"(https?://[^"]+\.(?:m3u8|mp4|mpd)[^"]*)"""", RegexOption.IGNORE_CASE),
                Regex(""""src"\s*:\s*"(https?://[^"]+\.(?:m3u8|mp4|mpd)[^"]*)"""", RegexOption.IGNORE_CASE),
                Regex("""setVideo(?:HLS|UrlHigh|UrlLow|URL)\s*\(\s*['"](https?://[^'"]+)['"]""", RegexOption.IGNORE_CASE),
            )
            for (pattern in patterns) {
                pattern.findAll(text).forEach { match ->
                    val raw = match.groupValues.last { it.startsWith("http") }
                    val cleaned = raw.trim().trimEnd('\\', ',', ')', ']', '}', '"', '\'')
                    if (looksLikeMedia(cleaned) || looksLikeMediaApi(cleaned)) found.add(cleaned)
                }
            }
            return found.toList()
        }

        private fun titleFromHtml(html: String): String? {
            val og = Regex("""property=["']og:title["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
            if (!og.isNullOrBlank()) return html_unescape(og)
            val title = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
            return title?.let(::html_unescape)?.trim()?.takeIf { it.isNotBlank() }
        }

        private fun durationFromHtml(html: String): Int {
            val match = Regex(""""duration"\s*:\s*(\d+)""", RegexOption.IGNORE_CASE).find(html)
            return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        }

        private fun parseJsStringList(value: String?): List<String> {
            return unescapeJsString(value)
                .split('\n')
                .map { it.trim().trim('"') }
                .filter { it.startsWith("http") }
        }

        private fun unescapeJsString(value: String?): String {
            if (value.isNullOrBlank() || value == "null") return ""
            var text = value.trim()
            if (text.startsWith("\"") && text.endsWith("\"")) {
                text = text.substring(1, text.length - 1)
            }
            return text
                .replace("\\u003C", "<")
                .replace("\\u003E", ">")
                .replace("\\u0026", "&")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\/", "/")
                .replace("\\\\", "\\")
        }

        private fun html_unescape(value: String): String {
            return value
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
        }
    }
}
