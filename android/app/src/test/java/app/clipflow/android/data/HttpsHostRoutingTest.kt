package app.clipflow.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpsHostRoutingTest {
    @Test
    fun choosesDifferentCertificateNameAndExpandsWildcard() {
        assertEquals(
            "reflected.net",
            HttpsHostRouting.chooseTlsHost(
                listOf("*.example.com", "reflected.net"),
                originalHost = "media.example.com",
            ),
        )
        assertEquals(
            "clipflow.reflected.net",
            HttpsHostRouting.chooseTlsHost(
                listOf("*.reflected.net"),
                originalHost = "media.example.com",
            ),
        )
        assertNull(HttpsHostRouting.chooseTlsHost(listOf("media.example.com"), "media.example.com"))
    }
}
