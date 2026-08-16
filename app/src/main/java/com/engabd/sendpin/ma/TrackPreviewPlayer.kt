package com.engabd.sendpin.ma

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Auditioning a track, without touching the queue.
 *
 * Music Assistant hands back a short preview URL and this plays it locally on the
 * phone. Tapping the same track again stops it, as does starting another one.
 *
 * ExoPlayer rather than the deprecated `android.media.MediaPlayer`: it handles audio
 * focus natively through `setAudioAttributes(…, handleAudioFocus = true)`.
 *
 * ## Why this is its own class
 *
 * It was ~75 lines inside `LibraryViewModel`, and it is the one part of that class
 * that owns a **player** — a real `ExoPlayer`, built and released on its own
 * schedule, with a lifetime nothing else in the library shares. Everything else in
 * that view model manipulates the same browse state; this manipulates a decoder.
 *
 * That is the difference that makes it extractable at all. Most of `LibraryViewModel`
 * cannot be split by moving code, because Kotlin has no partial classes and its
 * members share one pile of mutable state — reducing it further means extracting
 * collaborators like this one, deliberately, a piece at a time.
 */
class TrackPreviewPlayer(
    private val context: Context,
    private val repo: MaRepository,
    private val scope: CoroutineScope,
    /** Told what went wrong, so the message still reaches the same toast channel. */
    private val onMessage: (String) -> Unit,
) {

    private val _previewing = MutableStateFlow<String?>(null)

    /** The track currently being auditioned, if any. */
    val previewing: StateFlow<String?> = _previewing

    private var player: ExoPlayer? = null

    @OptIn(UnstableApi::class)
    fun toggle(item: MaItem) {
        if (_previewing.value == item.itemId) { stop(); return }
        stop()
        _previewing.value = item.itemId
        scope.launch {
            val url = try { repo.trackPreview(item.itemId, item.provider) } catch (_: Exception) { null }
            if (url.isNullOrBlank()) {
                _previewing.value = null
                onMessage("No preview available for this track")
                return@launch
            }
            // The user may have stopped it while the URL was in flight.
            if (_previewing.value != item.itemId) return@launch
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build()
                player = ExoPlayer.Builder(context)
                    .setAudioAttributes(attrs, /* handleAudioFocus = */ true)
                    .build().apply {
                        setMediaItem(MediaItem.fromUri(url))
                        prepare()
                        playWhenReady = true
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(state: Int) {
                                if (state == Player.STATE_ENDED) stop()
                            }
                        })
                    }
            } catch (_: Exception) {
                _previewing.value = null
                onMessage("Couldn't play the preview")
            }
        }
    }

    fun stop() {
        _previewing.value = null
        player?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        player = null
    }
}
