package app.clipflow.android.data

import app.clipflow.android.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val message: String,
    val apkUrl: String,
)

class UpdateChecker {
    fun check(
        feedUrl: String = DEFAULT_FEED_URL,
        currentCode: Int = BuildConfig.VERSION_CODE,
        currentName: String = BuildConfig.VERSION_NAME,
    ): UpdateInfo? {
        val connection = (URL(feedUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ClipFlow-Android/$currentName")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parse(body, currentCode)
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(body: String, currentCode: Int): UpdateInfo? {
        val trimmed = body.trim()
        val code = when {
            trimmed.startsWith("{") -> jsonInt(trimmed, "versionCode")
            else -> Regex("""<versionCode>\s*(\d+)\s*</versionCode>""", RegexOption.IGNORE_CASE)
                .find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        }
        val name = when {
            trimmed.startsWith("{") -> jsonString(trimmed, "versionName").ifBlank { jsonString(trimmed, "version") }
            else -> Regex("""<versionName>\s*([^<]+)\s*</versionName>""", RegexOption.IGNORE_CASE)
                .find(trimmed)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                .ifBlank {
                    Regex("""sparkle:shortVersionString="([^"]+)"""")
                        .find(trimmed)?.groupValues?.getOrNull(1).orEmpty()
                }
        }
        val apkUrl = when {
            trimmed.startsWith("{") -> jsonString(trimmed, "apkUrl").ifBlank { jsonString(trimmed, "url") }
            else -> Regex("""url="(https?://[^"]+\.apk[^"]*)"""", RegexOption.IGNORE_CASE)
                .find(trimmed)?.groupValues?.getOrNull(1).orEmpty()
                .ifBlank {
                    Regex("""<link>\s*(https?://[^<\s]+\.apk[^<\s]*)\s*</link>""", RegexOption.IGNORE_CASE)
                        .find(trimmed)?.groupValues?.getOrNull(1).orEmpty()
                }
        }
        val message = when {
            trimmed.startsWith("{") -> jsonString(trimmed, "message")
            else -> ""
        }
        if (code <= currentCode || apkUrl.isBlank() || !apkUrl.startsWith("http")) return null
        return UpdateInfo(
            versionName = name.ifBlank { code.toString() },
            versionCode = code,
            message = message.ifBlank { "ClipFlow Android ${name.ifBlank { code.toString() }} 업데이트가 있습니다." },
            apkUrl = apkUrl,
        )
    }

    private fun jsonString(body: String, key: String): String {
        val match = Regex(""""$key"\s*:\s*"((?:\\.|[^"\\])*)"""")
            .find(body)
            ?: return ""
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\/", "/")
    }

    private fun jsonInt(body: String, key: String): Int {
        return Regex(""""$key"\s*:\s*(-?\d+)""")
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
    }

    companion object {
        const val DEFAULT_FEED_URL =
            "https://raw.githubusercontent.com/kwd421/ClipFlow/main/docs/appcast-android.json"
    }
}
