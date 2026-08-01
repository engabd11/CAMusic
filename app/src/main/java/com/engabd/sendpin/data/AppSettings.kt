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
        private val SENDSPIN_CODEC = stringPreferencesKey("sendspin_codec")     // "auto" | "flac" | "pcm" | "opus"
        private val NAV_STREAM_FORMAT = stringPreferencesKey("nav_stream_format") // Subsonic `format=` ("raw" = original)
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
    val sendspinCodec: Flow<String> = context.dataStore.data.map { it[SENDSPIN_CODEC] ?: "auto" }

    /**
     * What Navidrome should send for a direct stream. "raw" is the stored file
     * untouched; anything else asks the server to transcode, which is worth it on a
     * slow connection and wasteful on a fast one.
     */
    val navStreamFormat: Flow<String> = context.dataStore.data.map { it[NAV_STREAM_FORMAT] ?: "raw" }

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

    /** Takes effect on the next connect — the format list is sent in the hello. */
    suspend fun setSendspinCodec(value: String) {
        context.dataStore.edit { it[SENDSPIN_CODEC] = value }
    }

    /** Applies to the next track: the format is a query parameter on the stream URL. */
    suspend fun setNavStreamFormat(value: String) {
        context.dataStore.edit { it[NAV_STREAM_FORMAT] = value }
    }
}
