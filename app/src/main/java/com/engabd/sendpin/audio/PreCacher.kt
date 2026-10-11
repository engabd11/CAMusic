package com.engabd.sendpin.audio

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import com.engabd.sendpin.data.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * "Fetch ahead": the next few songs, downloaded into the stream cache while the
 * current one plays.
 *
 * What it is for: a song that is fully on the phone starts instantly, survives a
 * tunnel or a lift, and plays on if the server goes away mid-album. The stream
 * cache is the same one the player already reads through ([MediaCache]), so there
 * is no second copy and nothing to manage: the player simply finds the bytes there.
 *
 * Off by default (it spends data and storage on songs that may be skipped), Wi-Fi
 * only unless told otherwise, and only while something is playing. Waits a few
 * seconds after every change of song or queue so skipping through a list does not
 * start a download per track, and abandons the fetch as soon as the queue moves on.
 */
@OptIn(UnstableApi::class)
class PreCacher(private val context: Context, private val player: LocalPlayer) {

    fun start(scope: CoroutineScope) {
        val settings = AppSettings(context)
        scope.launch {
            combine(
                combine(settings.precacheAhead, settings.precacheWifiOnly, unmetered()) { a, w, u -> Triple(a, w, u) },
                player.playing,
                player.queue,
                player.index,
            ) { (ahead, wifiOnly, unmetered), playing, _, _ ->
                val go = PreCachePlan.allowed(ahead, playing, unmetered, wifiOnly, remotePlayer = player.remote != null)
                // Read here, on the main thread, where the player may be asked.
                if (go) PreCachePlan.targets(player.upcomingSources(ahead), ahead) else emptyList()
            }.distinctUntilChanged().collectLatest { targets ->
                if (targets.isEmpty()) return@collectLatest
                delay(SETTLE_MS)
                withContext(Dispatchers.IO) {
                    for (url in targets) {
                        currentCoroutineContext().ensureActive()
                        fetch(url)
                    }
                }
            }
        }
    }

    /** All of [url] into the cache, unless it is there already. Cancellable between reads. */
    private suspend fun fetch(url: String) {
        if (runCatching { MediaCache.isFullyCached(context, url) }.getOrDefault(false)) {
            Log.d(TAG, "already on the phone: ${MediaCache.cacheKey(url).takeLast(48)}")
            return
        }
        val writer = CacheWriter(MediaCache.writingSource(context), DataSpec(android.net.Uri.parse(url)), null, null)
        // CacheWriter blocks; cancelling the coroutine sets its flag, and it stops at
        // the next read rather than finishing a 40 MB file nobody wants any more.
        val handle = currentCoroutineContext().job.invokeOnCompletion { if (it is CancellationException) writer.cancel() }
        try {
            writer.cache()
            Log.d(TAG, "fetched ahead: ${MediaCache.cacheKey(url).takeLast(48)}")
        } catch (e: IOException) {
            // Cancelled (InterruptedIOException), offline, or the server said no. The
            // player will stream it as before; nothing is lost but the head start.
            if (currentCoroutineContext().job.isActive) Log.w(TAG, "couldn't fetch ahead: ${e.message}")
        } finally {
            handle.dispose()
        }
    }

    /** Whether the network in use costs nothing per byte (Wi-Fi, Ethernet). */
    private fun unmetered(): Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }
        fun now(): Boolean = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        trySend(now())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }
            override fun onLost(network: Network) {
                trySend(now())
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged().onEach { Log.d(TAG, if (it) "on an unmetered network" else "on a metered network") }

    private companion object {
        const val TAG = "PreCacher"
        /** Long enough that skipping through a list does not start a fetch per song. */
        const val SETTLE_MS = 4_000L
    }
}
