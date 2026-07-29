package com.engabd.sendpin

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.engabd.sendpin.ma.MaApiClient
import com.engabd.sendpin.service.Playback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/** Holds the process-scoped [Playback] connection and the shared [MaApiClient] so they outlive the Activity. */
class SendpinApp : Application(), ImageLoaderFactory {
    val playback: Playback by lazy { Playback(this) }

    /**
     * Shared Music Assistant API client — one WebSocket, one auth handshake, one
     * reconnection loop for the entire app. ViewModels that need library browse /
     * queue / transport use this instead of opening their own connection.
     */
    val maApi: MaApiClient by lazy { MaApiClient() }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    /**
     * Album art is the loudest thing in this app, so quality is not negotiable:
     * covers decode at full ARGB_8888 (Coil's RGB_565 fallback bands badly on the
     * smooth gradients these covers are full of) and cache generously on disk, so
     * a re-opened album is instant and doesn't re-fetch through the MA proxy.
     *
     * The OkHttp client carries an interceptor that adds the MA bearer token to
     * any imageproxy request — without it the server returns 401 and every cover
     * comes back blank, which is why artists and albums had no art while podcasts
     * (which carry publicly-accessible URLs) did.
     */
    override fun newImageLoader(): ImageLoader {
        val authInterceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            val token = maApi.authToken
            // Only add auth to imageproxy requests that go to the MA server.
            if (token != null && url.contains("/imageproxy")) {
                chain.proceed(req.newBuilder().addHeader("Authorization", "Bearer $token").build())
            } else {
                chain.proceed(req)
            }
        }
        val http = OkHttpClient.Builder().addInterceptor(authInterceptor).build()
        return ImageLoader.Builder(this)
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
            .okHttpClient(http)
            .build()
    }

    companion object {
        lateinit var instance: SendpinApp
            private set
    }
}
