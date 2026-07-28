package app.clipflow.android

import android.app.Application
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

class ClipFlowApplication : Application() {
    @Volatile
    private var engineReady = false

    override fun onCreate() {
        super.onCreate()
        // Warm yt-dlp/ffmpeg/aria2 off the main thread so first analyze doesn't ANR.
        Thread({
            runCatching { ensureEngine() }
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
        Aria2c.getInstance().init(this)
        engineReady = true
    }
}
