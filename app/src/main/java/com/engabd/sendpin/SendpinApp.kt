package com.engabd.sendpin

import android.app.Application
import com.engabd.sendpin.service.Playback

/** Holds the process-scoped [Playback] connection so it outlives the Activity. */
class SendpinApp : Application() {
    val playback: Playback by lazy { Playback(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: SendpinApp
            private set
    }
}
