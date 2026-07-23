package app.clipflow.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CandidateSelectorTest {
    private fun candidate(
        id: String,
        height: Int,
        codec: String = "avc1",
        range: String = "SDR",
        audio: String = "none",
        size: Long = 0,
    ) = MediaCandidate(
        id = id,
        sourceUrl = "https://example.com/watch",
        mediaUrl = "https://example.com/$id.mp4",
        title = "Sample",
        uploader = "",
        thumbnailUrl = "",
        formatId = id,
        extension = "mp4",
        width = height * 16 / 9,
        height = height,
        fps = 30,
        videoCodec = codec,
        audioCodec = audio,
        dynamicRange = range,
        sizeBytes = size,
        durationSeconds = 60,
        isManifest = false,
    )

    @Test
    fun hdrIsExcludedByDefault() {
        val result = visibleCandidates(
            listOf(candidate("sdr", 1080), candidate("hdr", 2160, range = "HDR10")),
            DownloadPreferences(),
        )
        assertEquals(listOf("sdr"), result.map { it.id })
    }

    @Test
    fun duplicateQualityPrefersCombinedAndSizedFormat() {
        val result = visibleCandidates(
            listOf(
                candidate("video-only", 1080, size = 200),
                candidate("combined", 1080, audio = "aac", size = 100),
            ),
            DownloadPreferences(),
        )
        assertEquals("combined", result.single().id)
    }

    @Test
    fun parsesCommonTimecodes() {
        assertEquals(65, parseTimecode("01:05"))
        assertEquals(3723, parseTimecode("1:02:03"))
        assertNull(parseTimecode("1:x"))
        assertFalse(ClipRange().isSet)
    }

    @Test
    fun byteDisplayUsesFinderStyleDecimalUnits() {
        assertEquals("45.0 MB", formatBytes(45_000_000))
        assertEquals("1.2 GB", formatBytes(1_200_000_000))
    }
}
