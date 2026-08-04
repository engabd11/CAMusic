package com.engabd.sendpin.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "sendpin_settings")

/**
 * Persisted app settings: the active library backend (Music Assistant or a direct
 * Navidrome/OpenSubsonic server) and each one's server + login. (The Sendspin
 * player socket itself needs no login on a LAN.)
 */
class AppSettings(private val context: Context) {
    companion object {
        private val BACKEND = stringPreferencesKey("library_backend")         // "ma" | "subsonic"
        private val MA_BASE_URL = stringPreferencesKey("ma_base_url")          // e.g. http://192.168.0.10:8095
        private val MA_USERNAME = stringPreferencesKey("ma_username")
        private val MA_PASSWORD = stringPreferencesKey("ma_password")
        private val NAV_URL = stringPreferencesKey("nav_url")                  // e.g. http://192.168.0.10:4533
        private val NAV_USERNAME = stringPreferencesKey("nav_username")
        private val NAV_PASSWORD = stringPreferencesKey("nav_password")
        private val HA_URL = stringPreferencesKey("ha_url")                    // e.g. http://192.168.0.10:8123
        private val HA_TOKEN = stringPreferencesKey("ha_token")                // long-lived access token
        private val PLAYER_NAME = stringPreferencesKey("player_name")          // Sendspin client/hello name
        private val TARGET_PLAYER = stringPreferencesKey("target_player")      // MA player to play to / control ("" = this phone)
        private val NOW_PLAYING_LAYOUT = stringPreferencesKey("now_playing_layout") // "tab" (default) | "overlay"
        // Appearance. Defaults are the app as designed — OLED black with the accent
        // pulled from album art — so an untouched install looks exactly as before.
        private val THEME = stringPreferencesKey("theme")                       // oled | dark | light | system
        private val ACCENT_SOURCE = stringPreferencesKey("accent_source")       // album | dynamic | fixed
        private val FIXED_ACCENT = stringPreferencesKey("fixed_accent")         // ARGB hex, for accent_source=fixed
        private val PREFER_HI_RES = booleanPreferencesKey("prefer_hi_res")      // advertise 88.2/96 kHz too
        private val PREFER_FLAC = booleanPreferencesKey("prefer_flac")          // FLAC ahead of uncompressed PCM
        private val PREFER_ORIGINAL = booleanPreferencesKey("prefer_original")  // bypass MA when it would convert
        private val REGISTERED_NAME = stringPreferencesKey("registered_player_name")
        private val SENDSPIN_CODEC = stringPreferencesKey("sendspin_codec")     // "auto" | "flac" | "pcm" | "opus"
        private val NAV_STREAM_FORMAT = stringPreferencesKey("nav_stream_format") // Subsonic `format=` ("raw" = original)
        private val BIT_PERFECT = booleanPreferencesKey("bit_perfect_24bit")     // 24-bit AudioTrack path when available
        private val PREFERRED_AUDIO_DEVICE_ID = stringPreferencesKey("preferred_audio_device_id") // USB DAC routing
        private val DOWNLOAD_STORAGE_CAP_MB = stringPreferencesKey("download_storage_cap_mb") // 0 = unlimited
        private val DOWNLOAD_WIFI_ONLY = booleanPreferencesKey("download_wifi_only") // skip downloads on mobile data
        private val RADIO_MODE = booleanPreferencesKey("radio_mode")            // MA keeps the music going past the queue
        private val STATIC_DELAY_MS = stringPreferencesKey("sendspin_static_delay_ms") // per-player latency trim
        private val REPLAY_GAIN = stringPreferencesKey("replay_gain_mode")      // off | track | album
        private val KEEP_ALIVE_ANNOUNCEMENTS = booleanPreferencesKey("keep_alive_announcements") // persist connection for TTS

        // Direct Hue Bridge Light Sync
        private val HUE_BRIDGE_IP = stringPreferencesKey("hue_bridge_ip")
        private val HUE_APP_KEY = stringPreferencesKey("hue_app_key")         // encrypted
        private val HUE_CLIENT_KEY = stringPreferencesKey("hue_client_key")   // encrypted (PSK)
        private val HUE_APP_ID = stringPreferencesKey("hue_app_id")           // hue-application-id for DTLS PSK identity
        private val HUE_CONFIG_ID = stringPreferencesKey("hue_entertainment_config_id") // entertainment area UUID
        private val LIGHT_SYNC_MODE = stringPreferencesKey("light_sync_mode") // "ha" | "direct"
        private val LIGHT_SYNC_ENABLED = booleanPreferencesKey("light_sync_enabled") // direct mode master toggle
        private val LIGHT_SYNC_INTENSITY = stringPreferencesKey("light_sync_intensity") // subtle|medium|high|intense|extreme
        private val LIGHT_SYNC_EFFECT = stringPreferencesKey("light_sync_effect") // music|movies|fireworks
        private val LIGHT_SYNC_COLOR = stringPreferencesKey("light_sync_color") // colour scheme wire key
        private val LIGHT_SYNC_BRIGHTNESS = stringPreferencesKey("light_sync_brightness") // 5..100
        private val LIGHT_SYNC_TIMING = stringPreferencesKey("light_sync_timing") // -500..500 ms

        /**
         * How far the sync trim can be pushed either way. Matches the range Music
         * Assistant's own `sendspin_sync_delay` control offers, so the two agree;
         * only the positive half survives the clamp onto the wire, where the spec
         * caps `static_delay_ms` at 0..5000.
         */
        const val MAX_TRIM_MS = 2_000
    }

    val backend: Flow<String> = context.dataStore.data.map { it[BACKEND] ?: "ma" }
    val maBaseUrl: Flow<String> = context.dataStore.data.map { it[MA_BASE_URL] ?: "" }
    val maUsername: Flow<String> = context.dataStore.data.map { it[MA_USERNAME] ?: "" }
    val maPassword: Flow<String> = context.dataStore.data.map { Crypto.decrypt(it[MA_PASSWORD] ?: "") }
    val navUrl: Flow<String> = context.dataStore.data.map { it[NAV_URL] ?: "" }
    val navUsername: Flow<String> = context.dataStore.data.map { it[NAV_USERNAME] ?: "" }
    val navPassword: Flow<String> = context.dataStore.data.map { Crypto.decrypt(it[NAV_PASSWORD] ?: "") }
    val haUrl: Flow<String> = context.dataStore.data.map { it[HA_URL] ?: "" }
    val haToken: Flow<String> = context.dataStore.data.map { Crypto.decrypt(it[HA_TOKEN] ?: "") }
    val playerName: Flow<String> = context.dataStore.data.map { it[PLAYER_NAME] ?: "" }
    val targetPlayer: Flow<String> = context.dataStore.data.map { it[TARGET_PLAYER] ?: "" }
    val nowPlayingLayout: Flow<String> = context.dataStore.data.map { it[NOW_PLAYING_LAYOUT] ?: "tab" }
    val theme: Flow<String> = context.dataStore.data.map { it[THEME] ?: "oled" }
    val accentSource: Flow<String> = context.dataStore.data.map { it[ACCENT_SOURCE] ?: "album" }
    /** Stored as an ARGB hex string; empty means "use the built-in amber". */
    val fixedAccent: Flow<String> = context.dataStore.data.map { it[FIXED_ACCENT] ?: "" }
    val preferHiRes: Flow<Boolean> = context.dataStore.data.map { it[PREFER_HI_RES] ?: true }
    val preferFlac: Flow<Boolean> = context.dataStore.data.map { it[PREFER_FLAC] ?: true }
    val preferOriginal: Flow<Boolean> = context.dataStore.data.map { it[PREFER_ORIGINAL] ?: false }

    /**
     * Which codec this phone advertises to Music Assistant. "auto" offers all three in
     * preference order and lets the server pick; naming one narrows the advertised
     * list to that codec alone, which is the only way to *make* the server use it —
     * the server may only stream a format the client listed. Same lever the official
     * MA app pulls with its codec preference.
     */
    /**
     * The name this player was last successfully registered with. Music Assistant
     * keeps the name a player first registered under, so a change here is what tells
     * [com.engabd.sendpin.service.Playback.enablePlayer] it has to register anew.
     */
    val registeredPlayerName: Flow<String> = context.dataStore.data.map { it[REGISTERED_NAME] ?: "" }

    val sendspinCodec: Flow<String> = context.dataStore.data.map { it[SENDSPIN_CODEC] ?: "auto" }

    /**
     * What Navidrome should send for a direct stream. "raw" is the stored file
     * untouched; anything else asks the server to transcode, which is worth it on a
     * slow connection and wasteful on a fast one.
     */
    val navStreamFormat: Flow<String> = context.dataStore.data.map { it[NAV_STREAM_FORMAT] ?: "raw" }

    /**
     * Whether to request 24-bit bit-perfect playback from the AudioTrack path.
     * When true and the device supports `ENCODING_PCM_24BIT_PACKED` (API 31+,
     * which is our minSdk), [SendspinAudioEngine] builds a 24-bit AudioTrack
     * instead of truncating to 16-bit. The server must also be sending 24-bit
     * — see [FormatNegotiator.MAX_BIT_DEPTH], which is now 24.
     */
    val bitPerfect24Bit: Flow<Boolean> = context.dataStore.data.map { it[BIT_PERFECT] ?: false }

    /**
     * The system AudioDeviceInfo ID to route audio to, for USB DAC support.
     * Empty string means "let the system pick" (default speaker/headset).
     * Set by [com.engabd.sendpin.service.Playback] when a USB audio device is
     * detected; consumed by both [SendspinAudioEngine] and [LocalPlayer].
     */
    val preferredAudioDeviceId: Flow<String> = context.dataStore.data.map { it[PREFERRED_AUDIO_DEVICE_ID] ?: "" }

    /**
     * This player's latency trim, in milliseconds, for lining it up against other
     * speakers in a group.
     *
     * Sign follows the Sendspin spec, which is the opposite of how a "delay" control
     * usually reads: a **positive** value says this output path adds that much
     * latency, so the engine schedules frames that much *earlier*. Increase it when
     * the phone sounds late against the rest of the group.
     *
     * Stored as a string because DataStore has no int key helper in use here and the
     * other numeric settings already do this.
     */
    val staticDelayMs: Flow<Int> = context.dataStore.data.map { it[STATIC_DELAY_MS]?.toIntOrNull() ?: 0 }

    suspend fun setStaticDelayMs(ms: Int) = context.dataStore.edit {
        it[STATIC_DELAY_MS] = ms.coerceIn(-MAX_TRIM_MS, MAX_TRIM_MS).toString()
    }

    /**
     * ReplayGain handling on the local player: `off`, `track` or `album`.
     *
     * Defaults to `album`, which is the right answer for anyone who listens to
     * records rather than shuffled singles: album gain keeps the intended dynamics
     * *between* tracks, where track gain flattens a quiet interlude up to match the
     * loud song after it.
     *
     * Only the Navidrome/offline path reads this — Music Assistant applies gain
     * server-side in its own DSP pipeline, so applying it again here would double it.
     */
    val replayGainMode: Flow<String> = context.dataStore.data.map { it[REPLAY_GAIN] ?: "album" }

    suspend fun setReplayGainMode(mode: String) = context.dataStore.edit { it[REPLAY_GAIN] = mode }

    /** Download storage cap in MB. 0 means unlimited. */
    val downloadStorageCapMb: Flow<Int> = context.dataStore.data.map { it[DOWNLOAD_STORAGE_CAP_MB]?.toIntOrNull() ?: 0 }

    /** Only download over Wi-Fi, skip on mobile data. */
    val downloadWifiOnly: Flow<Boolean> = context.dataStore.data.map { it[DOWNLOAD_WIFI_ONLY] ?: false }

    /**
     * Whether to keep the Sendspin connection alive in the background for HA TTS
     * announcements. Default true — the connection service holds a wake lock and
     * wifi lock to receive announcements even while the app is backgrounded. Users
     * who don't use TTS can disable this to save battery; the connection will only
     * run during active playback and stop when idle.
     */
    val keepAliveForAnnouncements: Flow<Boolean> = context.dataStore.data.map { it[KEEP_ALIVE_ANNOUNCEMENTS] ?: true }

    suspend fun setKeepAliveForAnnouncements(value: Boolean) {
        context.dataStore.edit { it[KEEP_ALIVE_ANNOUNCEMENTS] = value }
    }

    /**
     * Radio mode: MA keeps generating similar tracks once the queue runs out.
     * Persisted rather than held in a view model because it is applied when
     * playback *starts* (`player_queues/play_media`), which the library does,
     * while the toggle that sets it lives on Now Playing.
     */
    val radioMode: Flow<Boolean> = context.dataStore.data.map { it[RADIO_MODE] ?: false }

    suspend fun setBackend(value: String) {
        context.dataStore.edit { it[BACKEND] = value }
    }

    suspend fun setMa(baseUrl: String, username: String, password: String) {
        context.dataStore.edit {
            it[MA_BASE_URL] = baseUrl; it[MA_USERNAME] = username; it[MA_PASSWORD] = Crypto.encrypt(password)
        }
    }

    suspend fun setNavidrome(url: String, username: String, password: String) {
        context.dataStore.edit {
            it[NAV_URL] = url; it[NAV_USERNAME] = username; it[NAV_PASSWORD] = Crypto.encrypt(password)
        }
    }

    suspend fun setBaseUrl(baseUrl: String) {
        context.dataStore.edit { it[MA_BASE_URL] = baseUrl }
    }

    suspend fun setHomeAssistant(url: String, token: String) {
        context.dataStore.edit { it[HA_URL] = url; it[HA_TOKEN] = Crypto.encrypt(token) }
    }

    suspend fun setPlayerName(name: String) {
        context.dataStore.edit { it[PLAYER_NAME] = name }
    }

    suspend fun setTargetPlayer(playerId: String) {
        context.dataStore.edit { it[TARGET_PLAYER] = playerId }
    }

    /**
     * A synchronous mirror of the theme, for the launch window only.
     *
     * The launch theme is XML and is applied before any of our code runs, and DataStore
     * is asynchronous by design — `onCreate` cannot ask it what colour the page should
     * be without blocking the main thread on I/O. So every theme write also lands in a
     * SharedPreferences file, which can be read synchronously. Without it, choosing
     * Light means a black flash on every cold start.
     *
     * DataStore stays the source of truth; this is a cache that only the first frame
     * reads.
     */
    private val bootPrefs = context.getSharedPreferences("sendpin_boot", Context.MODE_PRIVATE)

    val bootTheme: String get() = bootPrefs.getString("theme", "oled") ?: "oled"

    suspend fun setTheme(key: String) {
        bootPrefs.edit().putString("theme", key).apply()
        context.dataStore.edit { it[THEME] = key }
    }

    suspend fun setAccentSource(key: String) {
        context.dataStore.edit { it[ACCENT_SOURCE] = key }
    }

    suspend fun setFixedAccent(argbHex: String) {
        context.dataStore.edit { it[FIXED_ACCENT] = argbHex }
    }

    suspend fun setNowPlayingLayout(layout: String) {
        context.dataStore.edit { it[NOW_PLAYING_LAYOUT] = layout }
    }

    /** Takes effect on the next connect — the format list is sent in the hello. */
    suspend fun setPreferHiRes(value: Boolean) {
        context.dataStore.edit { it[PREFER_HI_RES] = value }
    }

    suspend fun setPreferFlac(value: Boolean) {
        context.dataStore.edit { it[PREFER_FLAC] = value }
    }

    suspend fun setPreferOriginal(value: Boolean) {
        context.dataStore.edit { it[PREFER_ORIGINAL] = value }
    }

    suspend fun setRegisteredPlayerName(name: String) {
        context.dataStore.edit { it[REGISTERED_NAME] = name }
    }

    /** Takes effect on the next connect — the format list is sent in the hello. */
    suspend fun setSendspinCodec(value: String) {
        context.dataStore.edit { it[SENDSPIN_CODEC] = value }
    }

    /** Applies to the next track: the format is a query parameter on the stream URL. */
    suspend fun setNavStreamFormat(value: String) {
        context.dataStore.edit { it[NAV_STREAM_FORMAT] = value }
    }

    /** Takes effect on the next stream/start — the AudioTrack is built per stream. */
    suspend fun setBitPerfect24Bit(value: Boolean) {
        context.dataStore.edit { it[BIT_PERFECT] = value }
    }

    /** Takes effect on the next stream/start or track open. */
    suspend fun setPreferredAudioDeviceId(value: String) {
        context.dataStore.edit { it[PREFERRED_AUDIO_DEVICE_ID] = value }
    }

    suspend fun setDownloadStorageCapMb(value: Int) {
        context.dataStore.edit { it[DOWNLOAD_STORAGE_CAP_MB] = value.toString() }
    }

    suspend fun setDownloadWifiOnly(value: Boolean) {
        context.dataStore.edit { it[DOWNLOAD_WIFI_ONLY] = value }
    }

    /** Applies to the next thing played, not the queue already running. */
    suspend fun setRadioMode(value: Boolean) {
        context.dataStore.edit { it[RADIO_MODE] = value }
    }

    // ── Direct Hue Bridge Light Sync ──────────────────────────────────────

    /** The bridge IP on the LAN, e.g. "192.168.0.42". */
    val hueBridgeIp: Flow<String> = context.dataStore.data.map { it[HUE_BRIDGE_IP] ?: "" }

    /** The Hue application key (username). Encrypted at rest. */
    val hueAppKey: Flow<String> = context.dataStore.data.map { Crypto.decrypt(it[HUE_APP_KEY] ?: "") }

    /** The Hue client key (PSK, 32-char hex). Encrypted at rest. */
    val hueClientKey: Flow<String> = context.dataStore.data.map { Crypto.decrypt(it[HUE_CLIENT_KEY] ?: "") }

    /** The hue-application-id used as the DTLS PSK identity. */
    val hueAppId: Flow<String> = context.dataStore.data.map { it[HUE_APP_ID] ?: "" }

    /** The entertainment area UUID to stream to. */
    val hueEntertainmentConfigId: Flow<String> = context.dataStore.data.map { it[HUE_CONFIG_ID] ?: "" }

    /** Which Light Sync transport: "ha" (Home Assistant) or "direct" (Hue Bridge). */
    val lightSyncMode: Flow<String> = context.dataStore.data.map { it[LIGHT_SYNC_MODE] ?: "ha" }

    /**
     * The direct-mode master toggle. The HA path keeps its own per-area `enabled`
     * switch in Home Assistant; direct mode has no HA to hold that state, so it
     * lives here. Off by default: pairing a bridge shouldn't start streaming to it.
     */
    val lightSyncEnabled: Flow<Boolean> = context.dataStore.data.map { it[LIGHT_SYNC_ENABLED] ?: false }

    /** Intensity mode: subtle / medium / high / intense / extreme. */
    val lightSyncIntensity: Flow<String> = context.dataStore.data.map { it[LIGHT_SYNC_INTENSITY] ?: "high" }

    /** Effect: music / movies / fireworks. */
    val lightSyncEffect: Flow<String> = context.dataStore.data.map { it[LIGHT_SYNC_EFFECT] ?: "music" }

    /** Colour scheme wire key. */
    val lightSyncColor: Flow<String> = context.dataStore.data.map { it[LIGHT_SYNC_COLOR] ?: "album_art_v2" }

    /** Master brightness ceiling (5..100). */
    val lightSyncBrightness: Flow<Int> = context.dataStore.data.map { it[LIGHT_SYNC_BRIGHTNESS]?.toIntOrNull() ?: 100 }

    /** Timing offset in ms (-500..500). */
    val lightSyncTiming: Flow<Int> = context.dataStore.data.map { it[LIGHT_SYNC_TIMING]?.toIntOrNull() ?: 0 }

    suspend fun setHueBridge(ip: String, appKey: String, clientKey: String, appId: String = "") {
        context.dataStore.edit {
            it[HUE_BRIDGE_IP] = ip
            it[HUE_APP_KEY] = Crypto.encrypt(appKey)
            it[HUE_CLIENT_KEY] = Crypto.encrypt(clientKey)
            if (appId.isNotBlank()) it[HUE_APP_ID] = appId
        }
    }

    suspend fun setHueConfigId(id: String) {
        context.dataStore.edit { it[HUE_CONFIG_ID] = id }
    }

    suspend fun setLightSyncMode(mode: String) {
        context.dataStore.edit { it[LIGHT_SYNC_MODE] = mode }
    }

    suspend fun setLightSyncEnabled(on: Boolean) {
        context.dataStore.edit { it[LIGHT_SYNC_ENABLED] = on }
    }

    suspend fun setLightSyncIntensity(intensity: String) {
        context.dataStore.edit { it[LIGHT_SYNC_INTENSITY] = intensity }
    }

    suspend fun setLightSyncEffect(effect: String) {
        context.dataStore.edit { it[LIGHT_SYNC_EFFECT] = effect }
    }

    suspend fun setLightSyncColor(color: String) {
        context.dataStore.edit { it[LIGHT_SYNC_COLOR] = color }
    }

    suspend fun setLightSyncBrightness(pct: Int) {
        context.dataStore.edit { it[LIGHT_SYNC_BRIGHTNESS] = pct.toString() }
    }

    suspend fun setLightSyncTiming(ms: Int) {
        context.dataStore.edit { it[LIGHT_SYNC_TIMING] = ms.toString() }
    }
}
