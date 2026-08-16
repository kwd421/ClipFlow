package app.clipflow.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
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
            runCatching { context.contentResolver.delete(android.net.Uri.parse(uriText), null, null) }
        }
        createdUris.clear()
    }

    @Test
    fun directPublicMp4DownloadsAndIsReadableAfterMediaStoreSave() {
        val mediaUrl = "https://media.w3.org/wai/perspective-videos/large-links-buttons-controls.mp4"
        val taskKey = "instrumented-smoke-${System.currentTimeMillis()}"
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_URL to mediaUrl,
                    DownloadWorker.KEY_DIRECT_URL to mediaUrl,
                    DownloadWorker.KEY_PREFER_DIRECT to true,
                    DownloadWorker.KEY_REFERER to "https://media.w3.org/",
                    DownloadWorker.KEY_FORMAT to "best",
                    DownloadWorker.KEY_OUTPUT_FORMAT to "mp4",
                    DownloadWorker.KEY_CONCURRENCY to 4,
                    DownloadWorker.KEY_TASK_KEY to taskKey,
                    DownloadWorker.KEY_TITLE to "clipflow-download-smoke",
                ),
            )
            .build()

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

        val outputUri = info?.outputData?.getString(DownloadWorker.OUTPUT_URI).orEmpty()
        val outputBytes = info?.outputData?.getLong(DownloadWorker.OUTPUT_BYTES, 0L) ?: 0L
        assertTrue("output URI is blank", outputUri.isNotBlank())
        assertTrue("reported output bytes must be positive", outputBytes > 0L)
        createdUris += outputUri

        val uri = android.net.Uri.parse(outputUri)
        val descriptorLength = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        assertTrue("saved file reports zero bytes", descriptorLength != 0L)
        context.contentResolver.openInputStream(uri).use { input ->
            assertNotNull("saved MediaStore URI cannot be opened", input)
            assertTrue("saved file is empty", input!!.read() >= 0)
        }
    }
}
