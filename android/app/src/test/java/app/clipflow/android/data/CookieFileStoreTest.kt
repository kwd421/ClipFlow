package app.clipflow.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieFileStoreTest {
    @Test
    fun parsesNetscapeCookiesIncludingHttpOnlyRows() {
        val cookies = parseNetscapeCookies(
            """
            # Netscape HTTP Cookie File
            .example.com	TRUE	/	TRUE	2000000000	session	abc
            #HttpOnly_.example.com	TRUE	/account	FALSE	0	auth	def
            invalid
            """.trimIndent(),
        )

        assertEquals(2, cookies.size)
        assertEquals("session", cookies[0].name)
        assertEquals("auth", cookies[1].name)
        assertEquals("/account", cookies[1].path)
    }

    @Test
    fun cookieHeaderMatchesDomainPathAndExpiry() {
        val contents = """
            # Netscape HTTP Cookie File
            .example.com	TRUE	/	FALSE	2000000000	a	1
            .example.com	TRUE	/video	TRUE	2000000000	b	2
            other.com	FALSE	/	FALSE	2000000000	c	3
            .example.com	TRUE	/	FALSE	1	expired	x
        """.trimIndent()
        val cookies = parseNetscapeCookies(contents)
        assertEquals(4, cookies.size)

        val header = cookies
            .asSequence()
            .filter { it.expiresAt <= 0 || it.expiresAt > 1_700_000_000 }
            .filter { !it.secure || true }
            .filter { cookie ->
                val host = "www.example.com"
                val domain = cookie.domain.removePrefix(".").lowercase()
                host == domain || (cookie.includeSubdomains && host.endsWith(".$domain"))
            }
            .filter { "/video/123".startsWith(it.path.ifBlank { "/" }) }
            .joinToString("; ") { "${it.name}=${it.value}" }

        assertTrue(header.contains("a=1"))
        assertTrue(header.contains("b=2"))
        assertTrue(!header.contains("c=3"))
        assertTrue(!header.contains("expired"))
    }

    @Test
    fun browserMediaHeightParsing() {
        assertEquals(1080, BrowserMediaFallback.heightFromUrl("https://cdn.example/hls/1080p/index.m3u8"))
        assertEquals(720, BrowserMediaFallback.heightFromUrl("https://cdn.example/720/master.m3u8"))
        assertTrue(BrowserMediaFallback.looksLikeMedia("https://x/a.mp4"))
        assertTrue(!BrowserMediaFallback.looksLikeMedia("https://x/page.html"))
    }

    @Test
    fun updateCheckerParsesJsonFeed() {
        val info = UpdateChecker().parse(
            """{"versionName":"0.2.0","versionCode":5,"apkUrl":"https://example.com/app.apk","message":"hi"}""",
            currentCode = 1,
        )
        assertEquals(5, info?.versionCode)
        assertEquals("https://example.com/app.apk", info?.apkUrl)
        assertEquals(null, UpdateChecker().parse(
            """{"versionName":"0.2.0","versionCode":1,"apkUrl":"https://example.com/app.apk"}""",
            currentCode = 1,
        ))
    }
}
