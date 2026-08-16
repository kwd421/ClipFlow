package app.clipflow.android

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

class ClipFlowApplication : Application() {
    @Volatile
    private var engineReady = false

    override fun onCreate() {
        super.onCreate()
        // Prepare native runtime and refresh yt-dlp away from the UI thread.
        Thread({
            runCatching {
                ensureEngine()
                refreshYtDlpIfStale()
            }.onFailure { error ->
                Log.w(LOG_TAG, "yt-dlp runtime preparation failed", error)
            }
        }, "clipflow-engine-init").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun ensureEngine() {
        if (engineReady) return
        YoutubeDL.getInstance().init(this)
        FFmpeg.getInstance().init(this)
        engineReady = true
    }

    /**
     * youtubedl-android bundles a yt-dlp snapshot, while the desktop app pins a
     * current yt-dlp release. Refresh the Android copy periodically so extractor
     * breakage does not linger until the APK itself is updated.
     *
     * This method shares the same monitor as [ensureEngine], so analysis/download
     * requests cannot execute while the yt-dlp file is being replaced.
     */
    @Synchronized
    fun refreshYtDlpIfStale(nowMillis: Long = System.currentTimeMillis()) {
        ensureEngine()
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        val lastAttempt = preferences.getLong(KEY_LAST_YTDLP_UPDATE_ATTEMPT, 0L)
        if (nowMillis - lastAttempt < YTDLP_UPDATE_INTERVAL_MILLIS) return

        // Record the attempt first: an offline device should not retry on every launch.
        preferences.edit().putLong(KEY_LAST_YTDLP_UPDATE_ATTEMPT, nowMillis).apply()
        runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(this, YoutubeDL.UpdateChannel.STABLE)
        }.onSuccess { status ->
            Log.i(LOG_TAG, "yt-dlp refresh status=$status version=${YoutubeDL.getInstance().version(this)}")
        }.onFailure { error ->
            Log.w(LOG_TAG, "yt-dlp refresh skipped/failed: ${error.message}")
        }
    }

    companion object {
        private const val LOG_TAG = "ClipFlowApplication"
        private const val PREFERENCES_NAME = "clipflow_engine"
        private const val KEY_LAST_YTDLP_UPDATE_ATTEMPT = "last_ytdlp_update_attempt"
        private const val YTDLP_UPDATE_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L
    }
}
