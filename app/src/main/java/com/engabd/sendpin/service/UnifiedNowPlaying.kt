package com.engabd.sendpin.service

import com.engabd.sendpin.audio.DeviceVolume
import com.engabd.sendpin.audio.LocalPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/**
 * What's playing right now, across all three of this phone's playback paths, as one
 * process-scoped [StateFlow] — for any surface that needs to *show* now-playing state
 * without itself being one of the two existing media notifications.
 *
 * This union is already computed twice: [SendspinService.currentShade] (Sendspin-self
 * vs. remote-MA) and inline in [com.engabd.sendpin.ui.screens.DrivingBar] (local vs.
 * everything-else). A third surface needing the same answer — starting with a future
 * Android Auto session — deriving it a third time is exactly the shape of bug
 * [PlaybackOwner] already exists to prevent for *transport*; this is that same fix
 * for *display*. [SendspinService] and [DrivingBar] are unchanged by this class: it
 * only gives a later caller somewhere to read from instead of writing a fourth copy.
 *
 * Precedence mirrors [SendspinService.currentShade] exactly: [PlaybackOwner]'s
 * *session* owner decides local vs. Sendspin first; within Sendspin, MA's own polled
 * player data is preferred whenever it describes the player this snapshot would
 * otherwise show (itself, or a genuinely different remote speaker) because the
 * Sendspin protocol's own `server/state.metadata` is optional and partial by spec.
 */
class UnifiedNowPlaying(
    private val playback: Playback,
    private val localPlayer: LocalPlayer,
    private val playbackOwner: PlaybackOwner,
    private val maNowPlaying: MaNowPlaying,
    private val deviceVolume: DeviceVolume,
) {
    enum class Owner { NONE, LOCAL, SENDSPIN_SELF, REMOTE }

    data class Snapshot(
        val owner: Owner,
        val title: String,
        val artist: String,
        val album: String,
        val artworkUrl: String?,
        val durationMs: Long,
        val positionMs: Long,
        val isPlaying: Boolean,
        val shuffleOn: Boolean,
        /** The remote speaker's name; null unless [owner] is [Owner.REMOTE]. */
        val playerName: String?,
        /** 0..100 — the phone's own media volume when local/self, MA's figure when remote. */
        val volumePercent: Int,
        val muted: Boolean,
    )

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private fun deviceVolumePercent(): Int = (deviceVolume.level.value * 100).toInt().coerceIn(0, 100)

    private fun snapshot(): Snapshot {
        if (playbackOwner.state.value.sessionOwner == PlaybackOwner.Who.LOCAL) {
            val track = localPlayer.current.value
            return Snapshot(
                owner = Owner.LOCAL,
                title = track?.title.orEmpty(),
                artist = track?.artist.orEmpty(),
                album = track?.album.orEmpty(),
                artworkUrl = track?.artUrl,
                durationMs = localPlayer.durationMs.value,
                positionMs = localPlayer.positionMs.value,
                isPlaying = localPlayer.playing.value,
                shuffleOn = localPlayer.shuffle.value,
                playerName = null,
                volumePercent = deviceVolumePercent(),
                muted = deviceVolumePercent() <= 0,
            )
        }

        val remote = maNowPlaying.now.value
        if (remote != null && (remote.isSelf || !playback.isPlaying.value)) {
            val isSelf = remote.isSelf
            return Snapshot(
                owner = if (isSelf) Owner.SENDSPIN_SELF else Owner.REMOTE,
                title = remote.title,
                artist = remote.artist,
                album = remote.album,
                artworkUrl = remote.artworkUrl,
                durationMs = remote.durationMs,
                // Position always comes from MA's `elapsed_time` /
                // `elapsed_time_last_updated` via the tracker in [MaNowPlaying],
                // whether this phone is the player or a remote speaker is. The
                // Sendspin path no longer sources position independently.
                positionMs = maNowPlaying.positionMs.value,
                isPlaying = if (isSelf) playback.isPlaying.value else remote.isPlaying,
                shuffleOn = maNowPlaying.shuffleActive.value,
                playerName = if (isSelf) null else remote.playerName,
                volumePercent = if (isSelf) deviceVolumePercent() else remote.volumeLevel,
                muted = if (isSelf) deviceVolumePercent() <= 0 else remote.muted,
            )
        }

        // No remote data (or a self/remote race mid-handover) — the Sendspin path's
        // own flows are what's left, and they describe this phone either way.
        return Snapshot(
            owner = Owner.SENDSPIN_SELF,
            title = playback.trackTitle.value,
            artist = playback.artist.value,
            album = playback.album.value,
            artworkUrl = playback.artworkUrl.value,
            durationMs = playback.durationMs.value,
            positionMs = maNowPlaying.positionMs.value,
            isPlaying = playback.isPlaying.value,
            shuffleOn = maNowPlaying.shuffleActive.value,
            playerName = null,
            volumePercent = deviceVolumePercent(),
            muted = deviceVolumePercent() <= 0,
        )
    }

    /** Live, deduplicated now-playing state across [LocalPlayer], Sendspin-self and a remote MA speaker. */
    val state: StateFlow<Snapshot> = combine(
        listOf<Flow<Any?>>(
            playbackOwner.state,
            localPlayer.current, localPlayer.playing, localPlayer.shuffle,
            localPlayer.positionMs, localPlayer.durationMs,
            playback.trackTitle, playback.artist, playback.album, playback.artworkUrl,
            playback.durationMs, playback.isPlaying,
            maNowPlaying.now, maNowPlaying.shuffleActive, maNowPlaying.positionMs,
            deviceVolume.level,
        )
    ) { snapshot() }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, snapshot())
}
