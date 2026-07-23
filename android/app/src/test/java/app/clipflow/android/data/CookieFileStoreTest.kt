package app.clipflow.android.data

import org.junit.Assert.assertEquals
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
}
