package app.clipflow.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubeDlAnalyzerTest {
    @Test
    fun readsOpenGraphDurationRegardlessOfAttributeOrder() {
        val html = """
            <meta content="299" data-extra="x" property="video:duration">
        """.trimIndent()

        assertEquals(299, durationSecondsFromPageHtml(html))
    }

    @Test
    fun readsIsoJsonLdDuration() {
        val html = """{"@type":"VideoObject","duration":"PT1H02M03S"}"""

        assertEquals(3723, durationSecondsFromPageHtml(html))
    }

    @Test
    fun prefersPageMetadataOverUnrelatedEmbeddedDuration() {
        val html = """
            <script>{"video_duration":5}</script>
            <meta property="video:duration" content="299">
        """.trimIndent()

        assertEquals(299, durationSecondsFromPageHtml(html))
    }
}
