package app.clipflow.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateSelectorTest {
    private fun candidate(
        id: String,
        height: Int,
        codec: String = "avc1",
        range: String = "SDR",
        audio: String = "none",
        size: Long = 0,
        title: String = "Sample",
        createdOrder: Long = 0,
        kind: RowKind = RowKind.Video,
        parentId: String = "",
        playlistIndex: Int = 0,
        expanded: Boolean = false,
    ) = MediaCandidate(
        id = id,
        sourceUrl = "https://example.com/watch",
        mediaUrl = "https://example.com/$id.mp4",
        title = title,
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
        kind = kind,
        parentId = parentId,
        playlistIndex = playlistIndex,
        createdOrder = createdOrder,
        expanded = expanded,
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
    fun audioOnlyItagNeverBecomesPreferredVideoCard() {
        val audioOnly = candidate("139", height = 0, codec = "none", audio = "mp4a.40.5", size = 669_711)
            .copy(extension = "m4a", width = 0)
        val video = candidate("137", height = 1080, codec = "avc1", audio = "none", size = 21_000_000)
        val preferred = preferredVisibleCandidate(listOf(audioOnly, video), DownloadPreferences())
        assertEquals("137", preferred?.id)
        assertTrue(preferred?.hasVideo == true)
        assertFalse(audioOnly.hasVideo)
    }

    @Test
    fun youtubeVideoOnlyUsesMergeFormatSelector() {
        val videoOnly = candidate("137", height = 1080, codec = "avc1", audio = "none")
            .copy(sourceUrl = "https://youtu.be/jmk282X0pzk", formatId = "137")
        assertTrue(videoOnly.formatSelector.contains("bestaudio"))
        assertFalse(videoOnly.prefersDirectUrl)
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

    @Test
    fun nameSortIsAlphabeticalNotJustReversed() {
        val rows = listOf(
            candidate("a", 720, title = "Charlie", createdOrder = 1),
            candidate("b", 720, title = "alpha", createdOrder = 2),
            candidate("c", 720, title = "Bravo", createdOrder = 3),
        )
        val ascending = sortCandidates(rows, SortState(SortKey.Name, descending = false))
        assertEquals(listOf("alpha", "Bravo", "Charlie"), ascending.map { it.title })
        val descending = sortCandidates(rows, SortState(SortKey.Name, descending = true))
        assertEquals(listOf("Charlie", "Bravo", "alpha"), descending.map { it.title })
    }

    @Test
    fun playlistChildrenStayUnderExpandedParent() {
        val rows = listOf(
            candidate("p", 0, title = "PL", kind = RowKind.Playlist, expanded = true, createdOrder = 10),
            candidate("c2", 720, title = "child2", kind = RowKind.PlaylistChild, parentId = "p", playlistIndex = 2, createdOrder = 12),
            candidate("c1", 720, title = "child1", kind = RowKind.PlaylistChild, parentId = "p", playlistIndex = 1, createdOrder = 11),
            candidate("other", 720, title = "solo", createdOrder = 5),
        )
        val sorted = sortCandidates(rows, SortState(SortKey.Latest, descending = true))
        assertEquals(listOf("p", "c1", "c2", "other"), sorted.map { it.id })
    }

    @Test
    fun extractMultipleUrlsFromPastedText() {
        val urls = extractUrls(
            """
            check https://youtu.be/abc
            and https://chzzk.naver.com/video/1,
            ignore ftp://nope
            """.trimIndent(),
        )
        assertEquals(
            listOf("https://youtu.be/abc", "https://chzzk.naver.com/video/1"),
            urls,
        )
    }
}
