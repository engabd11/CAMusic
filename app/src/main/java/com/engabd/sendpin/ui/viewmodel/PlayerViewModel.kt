package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.engabd.sendpin.SendpinApp

/**
 * Thin UI facade over the process-scoped [com.engabd.sendpin.service.Playback]
 * connection (held by [SendpinApp]). The connection itself lives outside the
 * ViewModel so it survives the Activity and keeps playing in the background.
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val pb = (application as SendpinApp).playback

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

    fun startDiscovery() = pb.startDiscovery()
    fun stopDiscovery() = pb.stopDiscovery()

    fun connectToServer(url: String, username: String = "", password: String = "", playerName: String = "") =
        pb.connectToServer(url, username, password, playerName)

    /** [name] blank keeps the saved one. */
    fun enablePlayer(name: String = "") = pb.enablePlayer(name)
    fun disablePlayer() = pb.disablePlayer()

    fun onPlayPause() = pb.onPlayPause()
    fun onMediaNext() = pb.onMediaNext()
    fun onMediaPrevious() = pb.onMediaPrevious()
    fun onMediaSeek(positionSec: Int) = pb.onMediaSeek(positionSec)
    fun onVolumeChange(vol: Float) = pb.onVolumeChange(vol)
    fun disconnect() = pb.disconnect()

    // The connection is process-scoped; don't tear it down when the screen goes away.
    override fun onCleared() {
        super.onCleared()
        pb.stopDiscovery()
    }
}
