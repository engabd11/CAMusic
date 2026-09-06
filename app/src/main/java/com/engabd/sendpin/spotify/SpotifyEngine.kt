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
    fun get(context: Context, username: String, password: String): Session {
        session?.let { if (it.reconnecting()) return it }
        close()
        val conf = Session.Configuration.Builder()
            .setCacheEnabled(true)
            .setCacheDir(context.cacheDir.resolve("spotify"))
            .setDoCacheCleanUp(true)
            .build()
        val s = Session.Builder(conf)
            .setDeviceName("CAMusic")
            .setDeviceId(com.engabd.sendpin.discovery.PlayerIdentity.getPlayerId(context))
            .setPreferredLocale("en")
            .userPass(username, password)
            .create()
        session = s
        val pconf = PlayerConfiguration.Builder()
            .setOutput(PlayerConfiguration.AudioOutput.CUSTOM)
            .setOutputClass(SpotifySink::class.java.name)
            .setOutputClassParams(arrayOf<Any>(tapHolder))
            .setPreferredQuality(AudioQuality.VERY_HIGH)
            .setEnableNormalisation(false)
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

    /** Tear down session and player. Safe to call when nothing is live. */
    @Synchronized
    fun close() {
        _playing.value = false
        player?.close()
        player = null
        session?.close()
        session = null
    }

    /** The tap holder lives past sessions; the sink reads the tap through it. */
    class TapHolder(val tap: AudioAnalysisTap)
}
