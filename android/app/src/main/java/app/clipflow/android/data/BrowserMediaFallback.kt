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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Desktop "browser DOM/network capture" equivalent for Android.
 * Loads the page in a WebView and collects media URLs from network requests.
 */
class BrowserMediaFallback(
    private val context: Context,
    private val cookieStore: CookieFileStore,
) {
    fun capture(url: String, timeoutSeconds: Long = 25): AnalysisResult {
        val mediaUrls = linkedSetOf<String>()
        val titleHolder = arrayOf("브라우저 캡처")
        val done = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        var webView: WebView? = null

        main.post {
            try {
                injectCookies(url)
                webView = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.userAgentString = YoutubeDlAnalyzer.BROWSER_USER_AGENT
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            titleHolder[0] = view?.title?.takeIf { it.isNotBlank() } ?: titleHolder[0]
                            view?.evaluateJavascript(
                                """
                                (function(){
                                  var urls=[];
                                  document.querySelectorAll('video,source').forEach(function(el){
                                    if(el.src) urls.push(el.src);
                                    if(el.currentSrc) urls.push(el.currentSrc);
                                  });
                                  return urls.join('\n');
                                })();
                                """.trimIndent(),
                            ) { value ->
                                value.orEmpty()
                                    .trim('"')
                                    .replace("\\u003C", "<")
                                    .replace("\\/", "/")
                                    .split('\n')
                                    .map { it.trim().trim('"') }
                                    .filter { it.startsWith("http") }
                                    .forEach { mediaUrls.add(it) }
                            }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val requestUrl = request?.url?.toString().orEmpty()
                            if (looksLikeMedia(requestUrl)) mediaUrls.add(requestUrl)
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                    loadUrl(url)
                }
            } catch (_: Throwable) {
                if (finished.compareAndSet(false, true)) done.countDown()
            }
        }

        // Poll until media appears or timeout.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            if (mediaUrls.any { looksLikeMedia(it) }) break
            try {
                Thread.sleep(400)
            } catch (_: InterruptedException) {
                break
            }
        }
        finished.set(true)
        done.countDown()
        main.post { webView?.apply { stopLoading(); destroy() } }

        val items = mediaUrls.filter(::looksLikeMedia).distinct()
        if (items.isEmpty()) error("브라우저 캡처에서 미디어 URL을 찾지 못했습니다.")
        val title = titleHolder[0]
        val candidates = items.mapIndexed { index, mediaUrl ->
            val height = heightFromUrl(mediaUrl)
            val isMp4 = mediaUrl.contains(".mp4", ignoreCase = true)
            MediaCandidate(
                id = "browser-$index-$height",
                sourceUrl = url,
                mediaUrl = mediaUrl,
                title = title,
                uploader = "",
                thumbnailUrl = "",
                formatId = "browser-$height",
                extension = when {
                    isMp4 -> "mp4"
                    mediaUrl.contains(".m3u8", true) -> "m3u8"
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
                durationSeconds = 0,
                isManifest = mediaUrl.contains(".m3u8", true) || mediaUrl.contains(".mpd", true),
                route = "browser",
            )
        }.sortedByDescending { it.height }
        return AnalysisResult(title, candidates)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun injectCookies(url: String) {
        val header = cookieStore.cookieHeaderFor(url)
        if (header.isBlank()) return
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        header.split(';').map { it.trim() }.filter { it.contains('=') }.forEach { pair ->
            manager.setCookie(url, pair)
        }
        manager.flush()
    }

    companion object {
        fun looksLikeMedia(url: String): Boolean {
            val lower = url.lowercase()
            if (!lower.startsWith("http")) return false
            return listOf(".m3u8", ".mpd", ".mp4", ".m4v", ".mov", ".webm").any { lower.contains(it) }
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
    }
}
