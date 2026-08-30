package app.clipflow.android.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal object WebViewProxyOverride {
    private const val CALLBACK_TIMEOUT_SECONDS = 5L
    private val lock = ReentrantLock()

    fun <T> use(context: Context, proxyUrl: String, block: () -> T): T = lock.withLock {
        require(Looper.myLooper() != Looper.getMainLooper()) {
            "WebView proxy capture cannot block the main thread."
        }
        set(context, proxyUrl)
        try {
            block()
        } finally {
            clear(context)
        }
    }

    private fun set(context: Context, proxyUrl: String) {
        awaitCallback(context, "WebView proxy setup") { done ->
            check(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                "This Android WebView does not support proxy override."
            }
            val config = ProxyConfig.Builder()
                .addProxyRule(proxyUrl)
                .build()
            ProxyController.getInstance().setProxyOverride(
                config,
                ContextCompat.getMainExecutor(context),
                done,
            )
        }
    }

    private fun clear(context: Context) {
        awaitCallback(context, "WebView proxy cleanup") { done ->
            ProxyController.getInstance().clearProxyOverride(
                ContextCompat.getMainExecutor(context),
                done,
            )
        }
    }

    private fun awaitCallback(
        context: Context,
        operation: String,
        start: (Runnable) -> Unit,
    ) {
        val completed = CountDownLatch(1)
        val error = AtomicReference<Throwable?>(null)
        Handler(Looper.getMainLooper()).post {
            try {
                start(Runnable { completed.countDown() })
            } catch (failure: Throwable) {
                error.set(failure)
                completed.countDown()
            }
        }
        check(completed.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "$operation timed out."
        }
        error.get()?.let { throw it }
    }
}
