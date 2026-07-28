package com.engabd.sendpin

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.engabd.sendpin.service.Playback

/** Holds the process-scoped [Playback] connection so it outlives the Activity. */
class SendpinApp : Application(), ImageLoaderFactory {
    val playback: Playback by lazy { Playback(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    /**
     * Album art is the loudest thing in this app, so quality is not negotiable:
     * covers decode at full ARGB_8888 (Coil's RGB_565 fallback bands badly on the
     * smooth gradients these covers are full of) and cache generously on disk, so
     * a re-opened album is instant and doesn't re-fetch through the MA proxy.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .bitmapConfig(Bitmap.Config.ARGB_8888)
        .allowRgb565(false)
        .crossfade(true)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("art"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .build()

    companion object {
        lateinit var instance: SendpinApp
            private set
    }
}
