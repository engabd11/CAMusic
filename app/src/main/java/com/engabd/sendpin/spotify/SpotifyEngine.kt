package com.engabd.sendpin.spotify

import android.content.Context
import com.engabd.sendpin.audio.AudioAnalysisTap
import com.engabd.sendpin.audio.AudioLead
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.gianlu.librespot.audio.decoders.AudioQuality
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.player.Player
import xyz.gianlu.librespot.player.PlayerConfiguration
import java.io.IOException

/**
 * The embedded Spotify client: one librespot [Session], one [Player], and the
 * analysis tap its sink feeds.
 *
 * Route B — the Spotify client *is* the player. Librespot connects to Spotify's
 * access points with the user's own Premium credentials, decodes in-process, and
 * writes PCM through [SpotifySink] into this phone's [android.media.AudioTrack];
 * the transport (queue, play, pause, seek) is driven app-side through
 * `SpotifyRemote` and lands here as [playerOrNull] calls. There is no ExoPlayer in
 * this path and no stream URL for anything to fetch — which is precisely the point:
 * the audio never leaves this process, so Light Sync reads it via [tap] with no
 * capture consent and no Spotify app installed.
 *
 * The session is process-scoped and rebuilt on demand: [get] creates it on first
 * use (login is a blocking, seconds-long TLS handshake to Spotify's access
 * points), and [close] tears down both session and player and clears the
 * singleton — matching `MusicSources`' rebuild-on-connect lifecycle, where a
 * re-registered stream handler must belong to the newest live client.
 */
object SpotifyEngine {

    /**
     * The tap the sink feeds. The capture path owns its own tap instance for the
     * same reason this one exists: `AudioAnalysisTap` is single-producer, and the
     * player's audio thread is this one's only writer.
     */
    val tap: AudioAnalysisTap by lazy { AudioAnalysisTap(lead) }

    /** Session clock only — librespot gives no media-time anchor to the sink. */
    internal val lead: AudioLead by lazy { AudioLead() }

    /** Given to the sink; survives session rebuilds so the tap is created once. */
    internal val tapHolder = TapHolder(tap)

    @Volatile
    private var session: Session? = null

    @Volatile
    private var player: Player? = null

    /**
     * Whether the embedded player is producing sound — the light-sync feed
     * picker's question, answered from librespot's own events rather than
     * app-side bookkeeping so pause/resume from any transport path agrees.
     * Driven by the listener installed in [get]; defaults to false before the
     * first session exists.
     */
    val playing: StateFlow<Boolean> get() = _playing.asStateFlow()
    private val _playing = MutableStateFlow(false)

    /**
     * The live session, creating it if necessary. Blocking network work — call
     * off the main thread.
     *
     * @throws Session.SpotifyAuthenticationException when the credentials are
     *   refused; [IOException] when Spotify cannot be reached.
     */
    @Synchronized
    @Throws(IOException::class)
    fun get(
        context: Context,
        username: String,
        password: String,
        prefs: com.engabd.sendpin.library.ProviderSettings.SpotifyPrefs =
            com.engabd.sendpin.library.ProviderSettings.SpotifyPrefs(),
    ): Session {
        // A live session is reused only while it was built for the same account
        // and the same settings: the quality, normalisation, crossfade and device
        // name are baked into the player and the session at construction, so a
        // change on the settings page means a rebuild.
        session?.let {
            if (it.reconnecting() && builtFor == Triple(username, password, prefs)) return it
        }
        close()
        val conf = Session.Configuration.Builder()
            .setCacheEnabled(true)
            .setCacheDir(context.cacheDir.resolve("spotify"))
            .setDoCacheCleanUp(true)
            .build()
        val s = Session.Builder(conf)
            .setDeviceName(prefs.deviceName)
            // librespot insists on a 40-character hex device id (Spotify's own are
            // SHA-1s). The app's player id is a UUID — 36 characters — and handing it
            // over verbatim failed every login with "Device ID must be 40 chars long"
            // before a single packet went out. Hash it: stable per install, right shape.
            .setDeviceId(spotifyDeviceId(com.engabd.sendpin.discovery.PlayerIdentity.getPlayerId(context)))
            .setPreferredLocale("en")
            .userPass(username, password)
            .create()
        session = s
        builtFor = Triple(username, password, prefs)
        val pconf = PlayerConfiguration.Builder()
            .setOutput(PlayerConfiguration.AudioOutput.CUSTOM)
            .setOutputClass(SpotifySink::class.java.name)
            .setOutputClassParams(arrayOf<Any>(tapHolder))
            .setPreferredQuality(
                when (prefs.quality) {
                    com.engabd.sendpin.library.ProviderSettings.SpotifyQuality.NORMAL -> AudioQuality.NORMAL
                    com.engabd.sendpin.library.ProviderSettings.SpotifyQuality.HIGH -> AudioQuality.HIGH
                    com.engabd.sendpin.library.ProviderSettings.SpotifyQuality.VERY_HIGH -> AudioQuality.VERY_HIGH
                },
            )
            .setEnableNormalisation(prefs.normalise)
            .setAutoplayEnabled(prefs.autoplay)
            .setCrossfadeDuration(prefs.crossfadeSeconds * 1000)
            .setPreloadEnabled(prefs.preload)
            .build()
        val p = Player(pconf, s)
        p.addEventsListener(
            object : Player.EventsListener {
                override fun onContextChanged(player: Player, newUri: String) = Unit
                override fun onTrackChanged(
                    player: Player,
                    id: xyz.gianlu.librespot.metadata.PlayableId,
                    metadata: xyz.gianlu.librespot.audio.MetadataWrapper?,
                    userInitiated: Boolean,
                ) = Unit

                override fun onPlaybackEnded(player: Player) {
                    _playing.value = false
                }

                override fun onPlaybackPaused(player: Player, trackTime: Long) {
                    _playing.value = false
                }

                override fun onPlaybackResumed(player: Player, trackTime: Long) {
                    _playing.value = true
                }

                override fun onPlaybackFailed(player: Player, e: Exception) {
                    _playing.value = false
                }

                override fun onTrackSeeked(player: Player, trackTime: Long) = Unit
                override fun onMetadataAvailable(player: Player, metadata: xyz.gianlu.librespot.audio.MetadataWrapper) = Unit
                override fun onPlaybackHaltStateChanged(player: Player, halted: Boolean, trackTime: Long) = Unit
                override fun onInactiveSession(player: Player, timeout: Boolean) = Unit
                override fun onVolumeChanged(player: Player, volume: Float) = Unit
                override fun onPanicState(player: Player) {
                    _playing.value = false
                }

                override fun onStartedLoading(player: Player) = Unit
                override fun onFinishedLoading(player: Player) = Unit
            },
        )
        player = p
        return s
    }

    /** The live player, or null before the first successful login. */
    fun playerOrNull(): Player? = player

    /** The live session, or null before the first successful login. */
    fun sessionOrNull(): Session? = session

    /** What the live session was built for — account and settings — so a change rebuilds it. */
    @Volatile private var builtFor: Triple<String, String, com.engabd.sendpin.library.ProviderSettings.SpotifyPrefs>? = null

    /** Clear the Spotify cache on disk (librespot's audio + metadata cache). Safe while idle. */
    @Synchronized
    fun clearCache(context: Context) {
        close()
        context.cacheDir.resolve("spotify").deleteRecursively()
    }

    /** Tear down session and player. Safe to call when nothing is live. */
    @Synchronized
    fun close() {
        builtFor = null
        _playing.value = false
        player?.close()
        player = null
        session?.close()
        session = null
    }

    /** The tap holder lives past sessions; the sink reads the tap through it. */
    class TapHolder(val tap: AudioAnalysisTap)

    /** A 40-hex-character device id derived from [playerId] — see the `setDeviceId` note. */
    fun spotifyDeviceId(playerId: String): String =
        java.security.MessageDigest.getInstance("SHA-1")
            .digest(playerId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
