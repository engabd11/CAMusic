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

    /** Download storage cap in MB. 0 means unlimited. */
    val downloadStorageCapMb: Flow<Int> = context.dataStore.data.map { it[DOWNLOAD_STORAGE_CAP_MB]?.toIntOrNull() ?: 0 }

    /** Only download over Wi-Fi, skip on mobile data. */
    val downloadWifiOnly: Flow<Boolean> = context.dataStore.data.map { it[DOWNLOAD_WIFI_ONLY] ?: false }

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
}
