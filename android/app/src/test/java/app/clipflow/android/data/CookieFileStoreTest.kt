package app.clipflow.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieFileStoreTest {
    private fun cookieFixture(value: String): String = value.trimIndent().replace("\\t", "\t")

    @Test
    fun parsesNetscapeCookiesIncludingHttpOnlyRows() {
        val cookies = parseNetscapeCookies(
            cookieFixture(
                """
                # Netscape HTTP Cookie File
                .example.com\tTRUE\t/\tTRUE\t2000000000\tsession\tabc
                #HttpOnly_.example.com\tTRUE\t/account\tFALSE\t0\tauth\tdef
                invalid
                """,
            ),
        )

        assertEquals(2, cookies.size)
        assertEquals("session", cookies[0].name)
        assertFalse(cookies[0].httpOnly)
        assertEquals("auth", cookies[1].name)
        assertTrue(cookies[1].httpOnly)
        assertEquals("/account", cookies[1].path)
    }

    @Test
    fun cookieHeaderMatchesDomainPathAndExpiry() {
        val contents = cookieFixture(
            """
            # Netscape HTTP Cookie File
            .example.com\tTRUE\t/\tFALSE\t2000000000\ta\t1
            .example.com\tTRUE\t/video\tTRUE\t2000000000\tb\t2
            other.com\tFALSE\t/\tFALSE\t2000000000\tc\t3
            .example.com\tTRUE\t/\tFALSE\t1\texpired\tx
            """,
        )
        val cookies = parseNetscapeCookies(contents)
        assertEquals(4, cookies.size)

        val header = cookies
            .asSequence()
            .filter { it.expiresAt <= 0 || it.expiresAt > 1_700_000_000 }
            .filter { !it.secure || true }
            .filter { cookieDomainMatches(it, "www.example.com") }
            .filter { cookiePathMatches(it.path, "/video/123") }
            .joinToString("; ") { "${it.name}=${it.value}" }

        assertTrue(header.contains("a=1"))
        assertTrue(header.contains("b=2"))
        assertTrue(!header.contains("c=3"))
        assertTrue(!header.contains("expired"))
    }

    @Test
    fun hostOnlyCookieDoesNotMatchSubdomainAndPathUsesBoundary() {
        val hostOnly = NetscapeCookie(
            domain = "example.com",
            includeSubdomains = false,
            path = "/account",
            secure = false,
            expiresAt = 0,
            name = "sid",
            value = "1",
        )
        assertTrue(cookieDomainMatches(hostOnly, "example.com"))
        assertFalse(cookieDomainMatches(hostOnly, "www.example.com"))
        assertTrue(cookiePathMatches("/account", "/account/settings"))
        assertFalse(cookiePathMatches("/account", "/accounting"))
    }

    @Test
    fun automaticCookiesOverrideImportedCookiesByName() {
        assertEquals(
            "session=new; preference=compact; challenge=passed",
            mergeCookieHeaders(
                "session=old; preference=compact",
                "session=new; challenge=passed",
            ),
        )
    }

    @Test
    fun netscapeSerializationRoundTripsCookieValues() {
        val cookies = listOf(
            NetscapeCookie("example.com", false, "/", true, 0, "session", "a=b=c"),
            NetscapeCookie(".example.com", true, "/video", false, 2_000_000_000, "quality", "high", true),
        )

        assertEquals(cookies, parseNetscapeCookies(serializeNetscapeCookies(cookies)))
    }

    @Test
    fun browserMediaHeightParsing() {
        assertEquals(1080, BrowserMediaFallback.heightFromUrl("https://cdn.example/hls/1080p/index.m3u8"))
        assertEquals(720, BrowserMediaFallback.heightFromUrl("https://cdn.example/720/master.m3u8"))
        assertTrue(BrowserMediaFallback.looksLikeMedia("https://x/a.mp4"))
        assertTrue(!BrowserMediaFallback.looksLikeMedia("https://x/page.html"))
    }

    @Test
    fun browserFallbackExtractsThumbnailAndHlsEstimate() {
        val html = """
            <meta content="//cdn.example/thumb.jpg" property="og:image">
            <video poster="/fallback.jpg"></video>
        """.trimIndent()
        assertEquals(
            "https://cdn.example/thumb.jpg",
            BrowserMediaFallback.thumbnailFromHtml(html, "https://www.example.com/watch/1"),
        )

        val probe = BrowserMediaFallback.hlsProbeFromPlaylist(
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=3858705,RESOLUTION=1920x1080
            index.m3u8
            """.trimIndent(),
            fallbackDurationSeconds = 850,
        )
        assertEquals(1920, probe.width)
        assertEquals(1080, probe.height)
        assertEquals(409_987_406L, probe.sizeBytes)
        assertEquals(850, probe.durationSeconds)
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
