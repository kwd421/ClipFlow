package app.clipflow.android.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import app.clipflow.android.data.AnalysisResult
import app.clipflow.android.data.BrowserMediaFallback
import app.clipflow.android.data.CookieFileStore
import app.clipflow.android.data.NetscapeCookie
import app.clipflow.android.data.parseNetscapeCookies
import app.clipflow.android.model.MediaCandidate
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Visible browser capture — desktop 공용 폴백에 해당하는 Android 구현.
 *
 * Headless WebView is blocked by many CDNs (Cloudflare / AniLife). A real
 * on-screen WebView can complete TLS, pass challenges, and use cookies while
 * we intercept media network requests (netlog equivalent).
 */
class BrowserCaptureActivity : ComponentActivity() {
    private val mediaUrls = linkedSetOf<String>()
    private var pageTitle = "브라우저 캡처"
    private var htmlSnapshot = ""
    private var webView: WebView? = null
    private var statusView: TextView? = null
    private var useButton: Button? = null
    private var finished = false
    private var firstMediaAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank() || activeSession == null) {
            finishCapture(Result.failure(IllegalStateException("브라우저 캡처 세션이 없습니다.")))
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
        }
        statusView = TextView(this).apply {
            text = "브라우저 폴백 · 미디어 감지 중\n로그인/보안 확인이 있으면 이 화면에서 진행하세요."
            setTextColor(0xFFE8E8E8.toInt())
            textSize = 13f
            setPadding(28, 24, 28, 12)
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 0, 16, 12)
        }
        useButton = Button(this).apply {
            text = "감지된 미디어 사용"
            isEnabled = false
            setOnClickListener { completeIfPossible(force = true) }
        }
        val cancel = Button(this).apply {
            text = "취소"
            setOnClickListener {
                finishCapture(Result.failure(IllegalStateException("사용자가 브라우저 폴백을 취소했습니다.")))
            }
        }
        actions.addView(useButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val webHost = FrameLayout(this)
        root.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(webHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        injectCookies(url, activeSession!!.cookieStore)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.userAgentString = BrowserMediaFallback.DESKTOP_CHROME_UA
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    statusView?.text = "페이지 로딩 중…"
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?,
                ) {
                    if (failingUrl == url || failingUrl.isNullOrBlank()) {
                        statusView?.text = "페이지 오류: ${description ?: errorCode}"
                    }
                }

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    pageTitle = view?.title?.takeIf { it.isNotBlank() } ?: pageTitle
                    view?.evaluateJavascript(JS_COLLECT_MEDIA) { value ->
                        parseJsList(value).forEach { addMedia(it) }
                    }
                    view?.evaluateJavascript(
                        "(function(){return document.documentElement?document.documentElement.outerHTML:'';})();",
                    ) { value ->
                        val html = unescapeJs(value)
                        if (html.length > htmlSnapshot.length) {
                            htmlSnapshot = html
                            BrowserMediaFallback.extractMediaUrlsFromHtml(html, url).forEach { addMedia(it) }
                        }
                        refreshStatus()
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val requestUrl = request?.url?.toString().orEmpty()
                    if (
                        BrowserMediaFallback.looksLikeMedia(requestUrl) ||
                        BrowserMediaFallback.looksLikeMediaApi(requestUrl)
                    ) {
                        runOnUiThread { addMedia(requestUrl) }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            loadUrl(url)
        }
        webHost.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView?.canGoBack() == true) webView?.goBack()
                    else finishCapture(Result.failure(IllegalStateException("사용자가 브라우저 폴백을 취소했습니다.")))
                }
            },
        )

        // Auto-finish shortly after first concrete media (desktop netlog grace).
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (finished) return
                completeIfPossible(force = false)
                if (!finished) mainHandler.postDelayed(this, 500)
            }
        }, 500)
    }

    private fun addMedia(raw: String) {
        val url = raw.replace("\\/", "/").trim()
        if (!url.startsWith("http")) return
        if (!(BrowserMediaFallback.looksLikeMedia(url) || BrowserMediaFallback.looksLikeMediaApi(url))) return
        if (mediaUrls.add(url) && BrowserMediaFallback.looksLikeMedia(url) && firstMediaAt == 0L) {
            firstMediaAt = System.currentTimeMillis()
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val playable = mediaUrls.count { BrowserMediaFallback.looksLikeMedia(it) }
        val api = mediaUrls.size - playable
        useButton?.isEnabled = playable > 0
        statusView?.text = when {
            playable > 0 -> "미디어 ${playable}개 감지${if (api > 0) " · API $api" else ""}. 잠시 후 자동 적용하거나 버튼을 누르세요."
            else -> "브라우저 폴백 · 미디어 감지 중…\n필요하면 로그인/확인 후 재생을 시작해 주세요."
        }
    }

    private fun completeIfPossible(force: Boolean) {
        if (finished) return
        val playable = mediaUrls.filter { BrowserMediaFallback.looksLikeMedia(it) }
            .ifEmpty { mediaUrls.filter { BrowserMediaFallback.looksLikeMediaApi(it) } }
        if (playable.isEmpty()) {
            if (force) {
                finishCapture(Result.failure(IllegalStateException("아직 감지된 미디어가 없습니다. 재생을 시작해 주세요.")))
            }
            return
        }
        if (!force) {
            if (firstMediaAt == 0L) return
            if (System.currentTimeMillis() - firstMediaAt < 2_000) return
        }
        val title = pageTitle.takeIf { it.isNotBlank() } ?: "브라우저 캡처"
        val candidates = playable.distinct().mapIndexed { index, mediaUrl ->
            val height = BrowserMediaFallback.heightFromUrl(mediaUrl)
            val isManifest = mediaUrl.contains(".m3u8", true) ||
                mediaUrl.contains(".mpd", true) ||
                mediaUrl.contains("/media/hls", true)
            MediaCandidate(
                id = "browser-$index-$height",
                sourceUrl = intent.getStringExtra(EXTRA_URL).orEmpty(),
                mediaUrl = mediaUrl,
                title = title,
                uploader = "",
                thumbnailUrl = "",
                formatId = "browser-$height",
                extension = when {
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
                durationSeconds = 0,
                isManifest = isManifest,
                route = "browser",
            )
        }.sortedByDescending { it.height }
        finishCapture(Result.success(AnalysisResult(title, candidates, route = "browser")))
    }

    private fun finishCapture(result: Result<AnalysisResult>) {
        if (finished) return
        finished = true
        mainHandler.removeCallbacksAndMessages(null)
        try {
            webView?.stopLoading()
            webView?.destroy()
        } catch (_: Throwable) {
        }
        val session = activeSession
        activeSession = null
        session?.result?.set(result)
        session?.done?.countDown()
        finish()
    }

    private fun injectCookies(url: String, cookieStore: CookieFileStore) {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        if (host.isBlank()) return
        val contents = cookieStore.activeFile()?.readText() ?: return
        val now = System.currentTimeMillis() / 1000
        val origins = listOfNotNull(
            "https://$host",
            if (!host.startsWith("www.")) "https://www.$host" else null,
            url,
        )
        parseNetscapeCookies(contents)
            .filter { it.expiresAt <= 0 || it.expiresAt > now }
            .filter { cookie ->
                val domain = cookie.domain.removePrefix(".").lowercase()
                host.equals(domain, true) || host.endsWith(".$domain")
            }
            .forEach { cookie -> setNetscapeCookie(manager, origins, cookie) }
        manager.flush()
    }

    private fun setNetscapeCookie(manager: CookieManager, origins: List<String>, cookie: NetscapeCookie) {
        val domain = if (cookie.domain.startsWith(".")) cookie.domain else ".${cookie.domain}"
        val value = buildString {
            append(cookie.name).append('=').append(cookie.value)
            append("; Domain=").append(domain)
            append("; Path=").append(cookie.path.ifBlank { "/" })
            if (cookie.secure) append("; Secure")
        }
        origins.forEach { manager.setCookie(it, value) }
    }

    companion object {
        const val EXTRA_URL = "url"
        private var activeSession: Session? = null

        private data class Session(
            val cookieStore: CookieFileStore,
            val result: AtomicReference<Result<AnalysisResult>?>,
            val done: CountDownLatch,
        )

        /**
         * Blocking capture used from analyzer IO thread. Opens a visible WebView activity.
         */
        fun captureBlocking(
            context: Context,
            cookieStore: CookieFileStore,
            url: String,
            timeoutSeconds: Long = 120,
        ): AnalysisResult {
            val session = Session(
                cookieStore = cookieStore,
                result = AtomicReference(null),
                done = CountDownLatch(1),
            )
            activeSession = session
            val intent = Intent(context, BrowserCaptureActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            val finishedOk = session.done.await(timeoutSeconds, TimeUnit.SECONDS)
            if (!finishedOk) {
                activeSession = null
                error("브라우저 폴백 시간이 초과되었습니다.")
            }
            val result = session.result.get()
                ?: error("브라우저 폴백 결과가 비어 있습니다.")
            return result.getOrElse { throw it }
        }

        private const val JS_COLLECT_MEDIA = """
            (function(){
              var urls=[];
              function add(u){ if(u && typeof u==='string' && u.indexOf('http')===0) urls.push(u); }
              document.querySelectorAll('video,source,audio').forEach(function(el){
                add(el.src); add(el.currentSrc);
              });
              return urls.join('\n');
            })();
        """

        private fun parseJsList(value: String?): List<String> =
            unescapeJs(value).split('\n').map { it.trim().trim('"') }.filter { it.startsWith("http") }

        private fun unescapeJs(value: String?): String {
            if (value.isNullOrBlank() || value == "null") return ""
            var text = value.trim()
            if (text.startsWith("\"") && text.endsWith("\"")) text = text.substring(1, text.length - 1)
            return text
                .replace("\\u003C", "<")
                .replace("\\u003E", ">")
                .replace("\\u0026", "&")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\/", "/")
                .replace("\\\\", "\\")
        }
    }
}
