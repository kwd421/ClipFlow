package app.clipflow.android.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.clipflow.android.model.ClipRange
import app.clipflow.android.model.DownloadPreferences
import app.clipflow.android.model.SortState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DownloadWorkerSmokeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val createdUris = mutableListOf<String>()

    @After
    fun cleanup() {
        createdUris.forEach { uriText ->
            runCatching { context.contentResolver.delete(Uri.parse(uriText), null, null) }
        }
        createdUris.clear()
        SessionStore(context).clear()
    }

    @Test
    fun directMp4DownloadsAndIsReadableAfterMediaStoreSave() {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_URL to TEST_MP4,
                    DownloadWorker.KEY_DIRECT_URL to TEST_MP4,
                    DownloadWorker.KEY_PREFER_DIRECT to true,
                    DownloadWorker.KEY_REFERER to TEST_ORIGIN,
                    DownloadWorker.KEY_FORMAT to "best",
                    DownloadWorker.KEY_OUTPUT_FORMAT to "mp4",
                    DownloadWorker.KEY_CONCURRENCY to 4,
                    DownloadWorker.KEY_TASK_KEY to "direct-smoke-${System.currentTimeMillis()}",
                    DownloadWorker.KEY_TITLE to "clipflow-direct-smoke",
                ),
            )
            .build()

        val info = runWork(request)
        assertSavedOutput(info)
    }

    @Test
    fun ytDlpMp4DownloadsAndIsReadableAfterMediaStoreSave() {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    // Leave DIRECT_URL empty on purpose: exercise the same yt-dlp +
                    // bundled FFmpeg path used by YouTube/generic desktop-parity downloads.
                    DownloadWorker.KEY_URL to TEST_MP4,
                    DownloadWorker.KEY_PREFER_DIRECT to false,
                    DownloadWorker.KEY_REFERER to TEST_ORIGIN,
                    DownloadWorker.KEY_FORMAT to "best",
                    DownloadWorker.KEY_OUTPUT_FORMAT to "mp4",
                    DownloadWorker.KEY_CONCURRENCY to 4,
                    DownloadWorker.KEY_TASK_KEY to "ytdlp-smoke-${System.currentTimeMillis()}",
                    DownloadWorker.KEY_TITLE to "clipflow-ytdlp-smoke",
                ),
            )
            .build()

        val info = runWork(request)
        assertSavedOutput(info)
    }

    @Test
    fun directMediaClipDownloadsFromResolvedMediaUrl() {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_URL to "https://example.invalid/watch/fixture",
                    DownloadWorker.KEY_DIRECT_URL to TEST_MP4,
                    DownloadWorker.KEY_PREFER_DIRECT to true,
                    DownloadWorker.KEY_REFERER to TEST_ORIGIN,
                    DownloadWorker.KEY_FORMAT to "best",
                    DownloadWorker.KEY_OUTPUT_FORMAT to "mp4",
                    DownloadWorker.KEY_CONCURRENCY to 4,
                    DownloadWorker.KEY_START to 0,
                    DownloadWorker.KEY_END to 1,
                    DownloadWorker.KEY_TASK_KEY to "direct-clip-smoke-${System.currentTimeMillis()}",
                    DownloadWorker.KEY_TITLE to "clipflow-direct-clip-smoke",
                ),
            )
            .build()

        val info = runWork(request)
        assertSavedOutput(info)
    }

    @Test
    fun persistedSessionDoesNotRestoreStaleInputUrl() {
        val store = SessionStore(context)
        store.clear()
        store.save(
            PersistedSession(
                candidates = emptyList(),
                qualityPool = emptyMap(),
                tasks = emptyMap(),
                preferences = DownloadPreferences(),
                clipRange = ClipRange(),
                sort = SortState(),
                darkTheme = false,
                selectedIds = emptySet(),
                url = "https://example.com/old-video",
            ),
        )
        assertEquals("", store.load()?.url)
    }

    private fun runWork(request: androidx.work.OneTimeWorkRequest): WorkInfo {
        val manager = WorkManager.getInstance(context)
        manager.enqueue(request)

        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3)
        var info: WorkInfo? = null
        while (System.currentTimeMillis() < deadline) {
            info = manager.getWorkInfoById(request.id).get(15, TimeUnit.SECONDS)
            if (info?.state?.isFinished == true) break
            Thread.sleep(500)
        }

        assertNotNull("WorkManager never returned work info", info)
        val error = info?.outputData?.getString(DownloadWorker.ERROR).orEmpty()
        assertEquals("DownloadWorker failed: $error", WorkInfo.State.SUCCEEDED, info?.state)
        return info!!
    }

    private fun assertSavedOutput(info: WorkInfo) {
        val outputUri = info.outputData.getString(DownloadWorker.OUTPUT_URI).orEmpty()
        val outputBytes = info.outputData.getLong(DownloadWorker.OUTPUT_BYTES, 0L)
        val outputDuration = info.outputData.getInt(DownloadWorker.OUTPUT_DURATION_SECONDS, 0)
        assertTrue("output URI is blank", outputUri.isNotBlank())
        assertTrue("reported output bytes must be positive", outputBytes > 0L)
        assertTrue("reported output duration must be positive", outputDuration > 0)
        createdUris += outputUri

        val uri = Uri.parse(outputUri)
        val descriptorLength = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        assertTrue("saved file reports zero bytes", descriptorLength != 0L)
        context.contentResolver.openInputStream(uri).use { input ->
            assertNotNull("saved MediaStore URI cannot be opened", input)
            assertTrue("saved file is empty", input!!.read() >= 0)
        }
    }

    companion object {
        // 10.0.2.2 is the Android emulator alias for the CI host. The workflow
        // generates and serves this MP4 locally so the smoke test has no CDN or
        // anti-bot dependency.
        private const val TEST_ORIGIN = "http://10.0.2.2:8765/"
        private const val TEST_MP4 = "${TEST_ORIGIN}sample.mp4"
    }
}
