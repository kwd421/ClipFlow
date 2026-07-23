package app.clipflow.android

import android.app.Application
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

class ClipFlowApplication : Application() {
    @Volatile
    private var engineReady = false

    @Synchronized
    fun ensureEngine() {
        if (engineReady) return
        YoutubeDL.getInstance().init(this)
        FFmpeg.getInstance().init(this)
        Aria2c.getInstance().init(this)
        engineReady = true
    }
}
