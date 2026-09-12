package com.engabd.sendpin.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * An audio output that streams PCM over the network rather than to the
 * Android mixer.
 *
 * The phone still owns the queue and the decoder — ExoPlayer runs as
 * normal — but the audio sink is redirected from the device's DAC to
 * this output, which feeds PCM frames to a remote receiver.
 *
 * This is **not** a [RemotePlayback]: the queue, the decode and the Light
 * Sync tap all stay local. Only the output changes. See [AirPlayOutput]
 * for the first concrete implementation.
 *
 * ## Why this is separate from [RemotePlayback]
 *
 * [RemotePlayback] is for players that own their own queue and decode
 * their own audio — MPD, foobar2000, Music Assistant. The phone is a
 * remote control, not the audio source.
 *
 * A network output is the opposite: the phone IS the audio source. It
 * decodes, it owns the queue, and it streams the decoded PCM to a dumb
 * receiver. The receiver is a speaker, not a player. This is closer to
 * [AudioOutputs] (USB DAC, Bluetooth, built-in speaker) than to
 * [RemotePlayback] — it's an output route, not a transport handoff.
 *
 * ## Light Sync
 *
 * When a [NetworkOutput] is active, the [AudioAnalysisTap] still works:
 * the [AirPlayOutputProcessor] sits in the audio chain alongside it, and
 * the tap analyses the same PCM that reaches the network output. This is
 * actually cleaner than the Music Assistant path, where the tap has to
 * reconstruct timing from a network stream.
 */
interface NetworkOutput {

    /** Whether audio is currently flowing to the remote device. */
    val connected: StateFlow<Boolean>

    /** Name of the connected device, for the Now Playing badge. */
    val deviceName: StateFlow<String?>

    /**
     * Start streaming to the discovered device.
     *
     * @param host the receiver's IP address (dotted-quad from mDNS)
     * @param port the receiver's RTSP port (7000 for most AirPlay devices)
     * @param name the receiver's display name (from mDNS TXT)
     * @param authMode how the receiver wants to be authenticated
     * @param airplay2 true for the encrypted AP2 path, false for legacy RAOP
     * @param deviceId the mDNS instance id (used to key stored credentials)
     * @param credentialsJson stored long-term pairing credentials (empty = first pair)
     */
    suspend fun connect(
        host: String,
        port: Int = 7000,
        name: String,
        authMode: AuthMode = AuthMode.HAP_PIN,
        airplay2: Boolean = true,
        deviceId: String = "",
        credentialsJson: String = "",
    )

    /** Stop streaming and release the session. */
    suspend fun disconnect()

    /**
     * Feed a buffer of 16-bit interleaved stereo PCM.
     *
     * Called from the [AirPlayOutputProcessor] on ExoPlayer's audio thread.
     * Non-blocking: if the native ring buffer is full, samples are dropped
     * and the receiver fills with silence.
     */
    fun writePcm(buffer: ByteArray, offset: Int, length: Int)

    /** Set the receiver's volume, 0..1. */
    fun setVolume(volume: Float)

    /** Submit the on-screen PIN for HAP PIN pairing. */
    fun submitPin(pin: String)

    /** Whether the receiver is showing a PIN and waiting for the user to enter it. */
    fun isWaitingForPin(): Boolean

    /** Push now-playing metadata to the receiver. */
    fun setNowPlaying(
        title: String,
        artist: String,
        album: String,
        cover: ByteArray? = null,
        coverMime: String? = null,
    )

    /** Whether the native sender is active (handshaking or streaming). */
    fun isActive(): Boolean
}

/**
 * How an AirPlay receiver wants to be authenticated.
 *
 * Maps directly to the C++ [RaopDeviceInfo::Auth] enum in `raop_auth.h`.
 * The Kotlin side resolves this from the mDNS TXT record flags:
 * `pw=true` → [PASSWORD], `am=AirPort*` → [AUTH_SETUP], an `sf`/`features`
 * flag set with the HomeKit bits → [HAP_PIN] for an Apple TV,
 * [HAP_TRANSIENT] for a HomePod / macOS.
 */
enum class AuthMode(val nativeValue: Int) {
    /** Open receiver: plain RTSP, no auth at all. */
    NONE(0),
    /** RTSP digest auth (`pw=true`), reactive on a 401. */
    PASSWORD(1),
    /** MFiSAP one-shot POST (AirPort Express gen 2), reply ignored. */
    AUTH_SETUP(2),
    /** HAP transient pairing, fixed PIN 3939 (HomePod / macOS). */
    HAP_TRANSIENT(4),
    /** HAP on-screen 4-digit PIN (Apple TV 4+); stored creds skip it. */
    HAP_PIN(5),
}