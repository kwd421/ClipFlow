package app.clipflow.android.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import java.io.File
import java.net.URI

class CookieFileStore(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, 0)
    private val cookieFile = File(context.filesDir, "cookies/cookies.txt")

    fun activeFile(): File? = cookieFile.takeIf(File::isFile)

    fun label(): String = if (activeFile() != null) {
        preferences.getString(KEY_LABEL, null).orEmpty().ifBlank { "cookies.txt" }
    } else {
        "쿠키 미사용"
    }

    fun import(uri: Uri): String {
        val displayName = queryDisplayName(uri).ifBlank { "cookies.txt" }
        val contents = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("쿠키 파일을 읽을 수 없습니다.")
        if (parseNetscapeCookies(contents).isEmpty()) {
            error("Netscape 형식의 cookies.txt 파일이 아닙니다.")
        }
        cookieFile.parentFile?.mkdirs()
        cookieFile.writeText(contents)
        preferences.edit { putString(KEY_LABEL, displayName) }
        return displayName
    }

    fun clear() {
        cookieFile.delete()
        preferences.edit { remove(KEY_LABEL) }
    }

    fun cookieHeaderFor(url: String, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
        val target = runCatching { URI(url) }.getOrNull() ?: return ""
        val host = target.host?.lowercase().orEmpty()
        val path = target.rawPath.orEmpty().ifBlank { "/" }
        val secureRequest = target.scheme.equals("https", ignoreCase = true)
        if (host.isBlank()) return ""
        val contents = activeFile()?.readText() ?: return ""
        return parseNetscapeCookies(contents)
            .asSequence()
            .filter { it.expiresAt <= 0 || it.expiresAt > nowSeconds }
            .filter { !it.secure || secureRequest }
            .filter { cookieDomainMatches(it, host) }
            .filter { cookiePathMatches(it.path, path) }
            .joinToString("; ") { "${it.name}=${it.value}" }
    }

    private fun queryDisplayName(uri: Uri): String {
        return context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }.orEmpty()
    }

    companion object {
        private const val PREFERENCES_NAME = "clipflow"
        private const val KEY_LABEL = "cookie_file_label"
    }
}

internal data class NetscapeCookie(
    val domain: String,
    val includeSubdomains: Boolean,
    val path: String,
    val secure: Boolean,
    val expiresAt: Long,
    val name: String,
    val value: String,
    val httpOnly: Boolean = false,
)

internal fun cookieDomainMatches(cookie: NetscapeCookie, requestHost: String): Boolean {
    val host = requestHost.trim().trimEnd('.').lowercase()
    val domain = cookie.domain.removePrefix(".").trim().trimEnd('.').lowercase()
    if (host.isBlank() || domain.isBlank()) return false
    return host == domain || (cookie.includeSubdomains && host.endsWith(".$domain"))
}

internal fun cookiePathMatches(cookiePath: String, requestPath: String): Boolean {
    val cookie = cookiePath.ifBlank { "/" }
    val request = requestPath.ifBlank { "/" }
    if (request == cookie) return true
    if (!request.startsWith(cookie)) return false
    return cookie.endsWith('/') || request.getOrNull(cookie.length) == '/'
}

internal fun parseNetscapeCookies(contents: String): List<NetscapeCookie> {
    return contents.lineSequence().mapNotNull { rawLine ->
        val httpOnly = rawLine.startsWith("#HttpOnly_")
        val line = when {
            httpOnly -> rawLine.removePrefix("#HttpOnly_")
            rawLine.startsWith("#") -> return@mapNotNull null
            else -> rawLine
        }.trim()
        if (line.isBlank()) return@mapNotNull null
        val fields = line.split('\t')
        if (fields.size < 7) return@mapNotNull null
        NetscapeCookie(
            domain = fields[0],
            includeSubdomains = fields[1].equals("TRUE", ignoreCase = true),
            path = fields[2],
            secure = fields[3].equals("TRUE", ignoreCase = true),
            expiresAt = fields[4].toLongOrNull() ?: 0,
            name = fields[5],
            value = fields.subList(6, fields.size).joinToString("\t"),
            httpOnly = httpOnly,
        ).takeIf { it.domain.isNotBlank() && it.name.isNotBlank() }
    }.toList()
}
