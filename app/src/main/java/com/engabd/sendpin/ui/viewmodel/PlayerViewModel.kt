package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.ma.MaConfigEntry
import com.engabd.sendpin.ma.MaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * Thin UI facade over the process-scoped [com.engabd.sendpin.service.Playback]
 * connection (held by [SendpinApp]). The connection itself lives outside the
 * ViewModel so it survives the Activity and keeps playing in the background.
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val pb = (application as SendpinApp).playback

    private companion object {
        /**
         * What a per-player config entry has to be *about* to be worth showing.
         *
         * Substrings rather than exact keys, because MA wraps some of them with a
         * provider prefix and has renamed others between releases. Deliberately narrow:
         * a player's config also carries its name, its enabled flag, its sync delay and
         * its whole DSP chain, all of which already have better homes in this app.
         */
        val PLAYBACK_KEYS = listOf("crossfade", "gapless", "flow_mode")
    }

    // Discovery
    val discoveredServers = pb.discoveredServers
    val isDiscovering = pb.isDiscovering

    val playerId get() = pb.playerId
    /** What the hardware is, not what the player is called — see [savedPlayerName]. */
    val deviceName get() = pb.deviceName

    // Connection + playback state
    val connected = pb.connected
    val connectionStatus = pb.connectionStatus
    val trackTitle = pb.trackTitle
    val artist = pb.artist
    val album = pb.album
    val artworkUrl = pb.artworkUrl
    val isPlaying = pb.isPlaying
    val volume = pb.volume
    val currentFormat = pb.currentFormat
    val serverUrl = pb.serverUrl
    val connectionLog = pb.connectionLog

    val savedUsername = pb.savedUsername
    val savedPassword = pb.savedPassword
    val savedPlayerName = pb.savedPlayerName
    val hasSavedServer = pb.hasSavedServer
    val bootChecked = pb.bootChecked

    /** Why the last rename didn't take, or blank when it did. */
    val configStatus = pb.configStatus

    fun startDiscovery() = pb.startDiscovery()
    fun stopDiscovery() = pb.stopDiscovery()

    fun connectToServer(url: String, username: String = "", password: String = "", playerName: String = "") =
        pb.connectToServer(url, username, password, playerName)

    /** Blank [name] or [codec] keeps the saved one. Both are announced in the hello. */
    fun enablePlayer(name: String = "", codec: String = "") = pb.enablePlayer(name, codec)
    fun disablePlayer() = pb.disablePlayer()

    /** Rename without a reconnect — the displayed name is player config, not the hello. */
    fun renamePlayer(name: String) = pb.renamePlayer(name)

    /** Whether a codec change can be applied without reconnecting. */
    val canSwitchFormatLive: Boolean get() = pb.canSwitchFormatLive

    /** Switch codec on the live stream — see [Playback.requestFormat]. */
    fun requestFormat(codec: String) = pb.requestFormat(codec)

    /** Arrive in Music Assistant as a new player, when the old one's name won't budge. */
    fun reregister(name: String) = pb.reregister(name)

    fun onPlayPause() = pb.onPlayPause()
    fun onMediaNext() = pb.onMediaNext()
    fun onMediaPrevious() = pb.onMediaPrevious()
    fun onMediaSeek(positionSec: Int) = pb.onMediaSeek(positionSec)
    fun onVolumeChange(vol: Float) = pb.onVolumeChange(vol)
    fun disconnect() = pb.disconnect()

    // --- Music Assistant's own per-player settings -------------------------

    private val maRepo = MaRepository((application as SendpinApp).maApi)

    private val _playbackConfig = MutableStateFlow<List<MaConfigEntry>>(emptyList())

    /**
     * The playback settings Music Assistant keeps for this player — gapless,
     * crossfade, and whatever else the server's build declares alongside them.
     *
     * Filtered by *what the setting is about* rather than by an exact key list. MA has
     * spelled crossfade three ways across versions and wraps some keys with a
     * provider prefix, so matching on the subject is what survives the next rename.
     * Hidden entries are dropped: MA doesn't show them in its own UI and there is no
     * reason ours should.
     */
    val playbackConfig: StateFlow<List<MaConfigEntry>> = _playbackConfig

    /** Why the last config write didn't take, or null when it did. */
    private val _configError = MutableStateFlow<String?>(null)
    val configError: StateFlow<String?> = _configError

    fun loadPlaybackConfig() {
        if (!connected.value) { _playbackConfig.value = emptyList(); return }
        viewModelScope.launch {
            _playbackConfig.value = runCatching {
                maRepo.playerConfigEntries(playerId)
                    .filter { !it.hidden && PLAYBACK_KEYS.any { k -> k in it.plainKey } }
            }.getOrDefault(emptyList())
        }
    }

    /**
     * Write one of them back, then re-read.
     *
     * Re-read rather than assumed: this is an admin-only command on the server, and a
     * refusal that the UI paints over is exactly the failure mode the DSP screen was
     * built to stop repeating. What comes back is what the server actually holds.
     */
    fun setPlaybackConfig(entry: MaConfigEntry, value: JsonElement) {
        viewModelScope.launch {
            _configError.value = null
            runCatching { maRepo.savePlayerConfigValue(playerId, entry.key, value) }
                .onFailure {
                    _configError.value = it.message ?: "Music Assistant refused that change"
                }
            loadPlaybackConfig()
        }
    }

    // The connection is process-scoped; don't tear it down when the screen goes away.
    override fun onCleared() {
        super.onCleared()
        pb.stopDiscovery()
    }
}
