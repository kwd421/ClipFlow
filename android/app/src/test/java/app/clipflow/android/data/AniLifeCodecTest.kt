package app.clipflow.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniLifeCodecTest {
    @Test
    fun lzString_decompressesKnownSamples() {
        // Generated with official lz-string compressToUTF16
        val hello = intArrayOf(738, 19501, 19518, 25632, 32).map { it.toChar() }.joinToString("")
        assertEquals("hello", LzString.decompressFromUTF16(hello))

        val arr = intArrayOf(2016, 10817, 16556, 3202, 4147, 364, 849, 271, 8224, 32)
            .map { it.toChar() }
            .joinToString("")
        assertEquals("|¨abc¨¨def¨÷", LzString.decompressFromUTF16(arr))
    }

    @Test
    fun zipsonStringArray_parsesFiveStrings() {
        val parts = ZipsonStringArray.parse("|¨one¨¨two¨¨three¨¨four¨¨five¨÷")
        assertEquals(listOf("one", "two", "three", "four", "five"), parts)
    }

    @Test
    fun siteRouter_detectsAniLifeWatchUrl() {
        assertTrue(
            SiteRouter.isAniLife(
                "https://anilife.app/watch?id=2b78df5f-5ba3-41d5-aa37-771371258ae7",
            ),
        )
    }
}
