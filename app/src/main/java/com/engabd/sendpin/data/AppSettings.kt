package com.engabd.sendpin.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.engabd.sendpin.library.ServerConfig
import com.engabd.sendpin.library.ServerKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive

private val Context.dataStore by preferencesDataStore(name = "sendpin_settings")

/**
 * Persisted app settings: the active library backend (Music Assistant or a direct
 * Navidrome/OpenSubsonic server) and each one's server + login. (The Sendspin
 * player socket itself needs no login on a LAN.)
 */
class AppSettings(private val context: Context) {
    companion object {
        private val BACKEND = stringPreferencesKey("library_backend")         // "ma" | "subsonic"
        /**
         * Every configured server, as a JSON list of [ServerConfig].
         *
         * The app has outgrown one Music Assistant plus one Navidrome: Jellyfin,
         * Plex, Emby and the rest are all "another library", and a pair of fixed
         * credential slots cannot hold a third. Secrets inside are encrypted
         * individually rather than the blob as a whole, so a config can be read for
         * its address and label without decrypting its password.
         */
        private val SERVERS = stringPreferencesKey("servers")
        /** Which of [SERVERS] the Library tab browses. */
        private val ACTIVE_SERVER = stringPreferencesKey("active_server_id")
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
        private val SEEK_BAR_STYLE = stringPreferencesKey("seek_bar_style")     // "line" (default) | "wave"
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
        // "sendspin_exoplayer" was here and is deliberately not replaced. The
        // ExoPlayer engine is the only MA engine now, so a stored `false` from a
        // tester's earlier install must not keep silencing them — leaving the key
        // unread is what makes the upgrade unconditional.
        private val SENDSPIN_OBOE = booleanPreferencesKey("sendspin_oboe") // experimental: native Oboe output instead of the platform AudioTrack
        private val PREFERRED_AUDIO_DEVICE_ID = stringPreferencesKey("preferred_audio_device_id") // USB DAC routing
        private val DOWNLOAD_STORAGE_CAP_MB = stringPreferencesKey("download_storage_cap_mb") // 0 = unlimited
        private val DOWNLOAD_WIFI_ONLY = booleanPreferencesKey("download_wifi_only") // skip downloads on mobile data
        private val RADIO_MODE = booleanPreferencesKey("radio_mode")            // keep the music going past the queue
        private val NAV_FADE_SECONDS = stringPreferencesKey("nav_fade_seconds") // 0 = off, gapless
        private val BEAT_MATCHED_CROSSFADE = booleanPreferencesKey("beat_matched_crossfade") // time the fade to land on a beat
        private val STATIC_DELAY_MS = stringPreferencesKey("sendspin_static_delay_ms") // per-player latency trim
        private val REPLAY_GAIN = stringPreferencesKey("replay_gain_mode")      // off | track | album
        private val LYRICS_OFFSET_MS = stringPreferencesKey("lyrics_offset_ms") // +ve = lyrics run late
        private val KEEP_ALIVE_ANNOUNCEMENTS = booleanPreferencesKey("keep_alive_announcements") // persist connection for TTS

        // Driving mode — a slim always-on-top transport for a phone in a cradle.
        private val DRIVING_ENABLED = booleanPreferencesKey("driving_enabled")
        private val DRIVING_MECHANISM = stringPreferencesKey("driving_mechanism") // pip | overlay
        private val DRIVING_CAR_ADDRESS = stringPreferencesKey("driving_car_address") // bonded device MAC
        private val DRIVING_CAR_NAME = stringPreferencesKey("driving_car_name")       // for the settings row
        private val PAUSE_FOR_CALLS = booleanPreferencesKey("pause_for_calls")        // auto-pause playback while the phone rings/is on a call
        private val SPEED_LIMIT_ALERT_ENABLED = booleanPreferencesKey("speed_limit_alert_enabled")
        private val DRIVING_SPEED_LIMIT_KMH = stringPreferencesKey("driving_speed_limit_kmh")   // 0 = not set
        private val DRIVING_SPEED_TOLERANCE_PCT = stringPreferencesKey("driving_speed_tolerance_pct")
        private val SPEED_ADAPTIVE_VOLUME = booleanPreferencesKey("speed_adaptive_volume")

        // Self-hosted crash reporting
        private val CRASH_GITHUB_REPO = stringPreferencesKey("crash_github_repo") // owner/repo, e.g. engabd11/CAMusic
        private val CRASH_GITHUB_TOKEN = stringPreferencesKey("crash_github_token") // encrypted PAT for auto-submit
        private val CRASH_AUTO_UPLOAD = booleanPreferencesKey("crash_auto_upload") // false = manual share only

        // Direct Hue Bridge Light Sync
        private val HUE_BRIDGE_IP = stringPreferencesKey("hue_bridge_ip")
        private val HUE_APP_KEY = stringPreferencesKey("hue_app_key")         // encrypted
        private val HUE_CLIENT_KEY = stringPreferencesKey("hue_client_key")   // encrypted (PSK)
        private val HUE_APP_ID = stringPreferencesKey("hue_app_id")           // hue-application-id for DTLS PSK identity
        private val HUE_CONFIG_ID = stringPreferencesKey("hue_entertainment_config_id") // entertainment area UUID
        private val HUE_BRIDGE_ID = stringPreferencesKey("hue_bridge_id")
        private val LIGHT_SYNC_MODE = stringPreferencesKey("light_sync_mode") // "ha" | "direct"

        /**
         * Whether [lightSyncMode] follows the selected library.
         *
         * On by default, because the library is what decides where playback
         * actually happens: Navidrome always plays through this phone's own
         * player, which the direct bridge path taps, while Music Assistant plays
         * to whatever speaker is targeted and is Home Assistant's business.
         *
         * Kept as an override rather than removed, because Music Assistant with
         * a Hue bridge and no Home Assistant is a real setup that deriving the
         * mode purely from the library would strand. Touching the Settings
         * toggle clears this.
         */
        private val LIGHT_SYNC_MODE_AUTO = booleanPreferencesKey("light_sync_mode_auto")
        private val LIGHT_SYNC_ENABLED = booleanPreferencesKey("light_sync_enabled") // direct mode master toggle
        private val LIGHT_SYNC_INTENSITY = stringPreferencesKey("light_sync_intensity") // auto|subtle|medium|high|intense|extreme
        /** Rungs Auto may choose between, comma-separated wire keys. */
        private val LIGHT_SYNC_AUTO_LEVELS = stringPreferencesKey("light_sync_auto_levels")
        private val LIGHT_SYNC_EFFECT = stringPreferencesKey("light_sync_effect") // music|movies|fireworks
        private val LIGHT_SYNC_COLOR = stringPreferencesKey("light_sync_color") // colour scheme wire key
        private val LIGHT_SYNC_BRIGHTNESS = stringPreferencesKey("light_sync_brightness") // 5..100

        /**
         * Advanced live tunables for the direct bridge path. Each key is a multiplier
         * on the active mode's relevant params, exactly as on the Home Assistant path.
         * Stored as a JSON object: {"reactivity":1.0,"glow":1.1,...}.
         */
        private val LIGHT_SYNC_TUNABLES = stringPreferencesKey("light_sync_tunables")
        /** Whether the direct Light Sync screen shows the advanced tunables. */
        private val LIGHT_SYNC_ADVANCED = booleanPreferencesKey("light_sync_advanced")

        /**
         * Whether tracks are analysed ahead of the show.
         *
         * On by default. Everything the direct path could learn from a live tap
         * it already does; what is left — an exact beat grid from the first bar,
         * the song's own loudness range, where the sections are — needs the whole
         * track, and there is no way to have that without reading it first. A
         * track already on the phone costs a few seconds of a background core;
         * a streamed one costs a download, which is what [LIGHT_SYNC_PRESCAN_WIFI]
         * is for.
         */
        private val LIGHT_SYNC_PRESCAN = booleanPreferencesKey("light_sync_prescan")

        /** Only fetch remote tracks for analysis on an unmetered network. */
        private val LIGHT_SYNC_PRESCAN_WIFI = booleanPreferencesKey("light_sync_prescan_wifi_only")

        /**
         * Let the room move with sounds that move: a swell or a panning source
         * travels across the lamps instead of only brightening them.
         *
         * **Off by default**, and deliberately so. Not all music has a stereo
         * sweep or a linear swell in it, and a gesture that fires on a track with
         * neither is worse than one that never fires — it makes every track look
         * like the ones it is supposed to distinguish. Until this has been judged
         * on real tracks in a real room, the honest default is off.
         */
        private val LIGHT_SYNC_SPATIAL = booleanPreferencesKey("light_sync_spatial")

        // Four creative light-show layers — see `docs/creative-light-shows.md`.
        // Each is additive on top of the existing Synco show and off by
        // default, for the same reason LIGHT_SYNC_SPATIAL is: unproven on a
        // real track in a real room until a user turns it on.
        private val MUSIC_DNA_ENABLED = booleanPreferencesKey("music_dna_enabled")
        private val EMOTIONAL_ARC_ENABLED = booleanPreferencesKey("emotional_arc_enabled")
        private val PHANTOM_STAGE_ENABLED = booleanPreferencesKey("phantom_stage_enabled")
        private val PHONE_CONDUCTOR_ENABLED = booleanPreferencesKey("phone_conductor_enabled")
        // Whether the onboarding wizard has been completed or skipped. When false, the
        // app shows the wizard instead of the main UI on launch.
        private val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val ONBOARDING_SKIPPED = booleanPreferencesKey("onboarding_skipped")
        // There is deliberately no light_sync_timing key. The Home Assistant path
        // exposes an offset because syncoV2 can only estimate where the speakers
        // are; the direct path measures the tap's lead over the AudioTrack exactly
        // and subtracts Hue's pipeline latency from it, so there is nothing left
        // for a user to trim. See hue/FrameDelayQueue.

        /**
         * How far the sync trim can be pushed either way. Matches the range Music
         * Assistant's own `sendspin_sync_delay` control offers, so the two agree;
         * only the positive half survives the clamp onto the wire, where the spec
         * caps `static_delay_ms` at 0..5000.
         */
        const val MAX_TRIM_MS = 2_000

        /** Two seconds either way covers every provider disagreement worth fixing. */
        const val MAX_LYRICS_OFFSET_MS = 2_000

        const val MODE_HA = "ha"
        const val MODE_DIRECT = "direct"

        /** Picture-in-Picture: no permission, small fixed window. The default. */
        const val DRIVING_PIP = "pip"

        /** `SYSTEM_ALERT_WINDOW`: driving-sized targets, free positioning. */
        const val DRIVING_OVERLAY = "overlay"
        const val BACKEND_SUBSONIC = "subsonic"
        const val BACKEND_MA = "ma"

        /**
         * Stable ids for the two servers an install predating the list already had.
         *
         * Fixed rather than generated, so the list synthesised from the old keys is
         * the same list on every read until something is written — a random id would
         * make the active-server pointer refer to a server that no longer exists the
         * moment the flow re-emitted.
         */
        private const val LEGACY_MA_ID = "legacy-music-assistant"
        private const val LEGACY_NAV_ID = "legacy-navidrome"

        private val serverJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * Individually-[Crypto]-encrypted legacy keys — decrypted before an export and
         * re-encrypted (under the *importing* device's own Keystore key) on the way
         * back in. [SERVERS] carries its own per-field encryption and is handled
         * separately in [exportSettings]/[importSettings], not through this set.
         */
        private val ENCRYPTED_KEY_NAMES = setOf(
            MA_PASSWORD.name, NAV_PASSWORD.name, HA_TOKEN.name,
            HUE_APP_KEY.name, HUE_CLIENT_KEY.name, CRASH_GITHUB_TOKEN.name,
        )

        /** Not a real preference key — [SERVERS]' decrypted export lives under this in the JSON. */
        private const val SERVERS_EXPORT_KEY = "__servers_v1"

        /**
         * Which Light Sync transport a library implies.
         *
         * The library decides where the audio actually comes out, which is what
         * the transport has to match. Navidrome always plays through this
         * phone's own ExoPlayer, and the direct bridge path taps exactly that.
         * Music Assistant plays to whatever speaker is targeted — possibly this
         * phone over Sendspin, which uses a raw AudioTrack the tap cannot see —
         * so Home Assistant, which follows the MA player entity, is the one that
         * can cover every case.
         */
        fun lightSyncModeFor(backend: String): String =
            if (backend == BACKEND_SUBSONIC) MODE_DIRECT else MODE_HA

        /**
         * The transport a library switch calls for, or null to leave it alone.
         *
         * Pulled out of the coordinator so the decision can be tested without a
         * DataStore, an Application and a Home Assistant: the coordinator around
         * it is now three lines, and this is the part with the rules in it.
         */
        fun lightSyncModeChange(backend: String, auto: Boolean, current: String): String? {
            if (!auto) return null  // the user pinned a transport by hand
            return lightSyncModeFor(backend).takeIf { it != current }
        }
    }

    // ── The server list ───────────────────────────────────────────────────
    //
    // Two things are true at once and both have to stay true: the app now keeps a
    // list of servers of many kinds, and half the app still reads the old fixed
    // keys. `Playback` reads MA's address, `App.kt` decides which tabs are alive
    // from `library_backend`, the download path reads Navidrome's credentials, and
    // Light Sync picks its transport from the backend. Rewriting all of that in the
    // same change as introducing the list is how a refactor becomes a week of
    // regressions.
    //
    // So the list is the source of truth and the old keys are a **mirror**: every
    // write through [saveServers] / [setActiveServer] restates them, and the first
    // read synthesises the list from them. Nothing downstream has to know yet.

    /**
     * The list as stored, falling back to the legacy keys when there is nothing to
     * read — or nothing readable. See [decodeServers] for why those two are the same
     * answer here.
     */
    private fun storedServers(
        prefs: androidx.datastore.preferences.core.Preferences,
    ): List<ServerConfig> = prefs[SERVERS]?.let { decodeServers(it) } ?: legacyServers(prefs)

    /** Every configured server, oldest first. Secrets are already decrypted. */
    val servers: Flow<List<ServerConfig>> = context.dataStore.data.map { prefs ->
        storedServers(prefs)
    }

    /** The id of the server the Library tab browses, or "" before anything is set up. */
    val activeServerId: Flow<String> = context.dataStore.data.map { prefs ->
        resolveActiveId(prefs, storedServers(prefs))
    }

    /**
     * Which server is active, given what is stored.
     *
     * Derived rather than read straight out of [ACTIVE_SERVER], because that key is
     * empty until something writes it and a stored id can outlive the server it named.
     * Falling back to the *old* `library_backend` key is what keeps an upgrade landing
     * on the library the user was already browsing — reading the key naively, and
     * defaulting to the first entry in the list, silently moved every existing install
     * from Navidrome to Music Assistant on first launch.
     */
    private fun resolveActiveId(
        prefs: androidx.datastore.preferences.core.Preferences,
        list: List<ServerConfig>,
    ): String =
        prefs[ACTIVE_SERVER]?.takeIf { id -> list.any { it.id == id } }
            ?: list.firstOrNull { it.kind.playsLocally == (prefs[BACKEND] == BACKEND_SUBSONIC) }?.id
            ?: list.firstOrNull()?.id
            ?: ""

    /** The active server itself, or null when nothing is set up. */
    val activeServer: Flow<ServerConfig?> =
        combine(servers, activeServerId) { list, id -> list.firstOrNull { it.id == id } }

    // ── Music Assistant player settings, stored on the MA server ──────────
    //
    // Player name, codec, format preferences, keep-alive, target player and the static
    // delay all describe *this phone as a player registered with one MA server*. They
    // were app-global keys, which reads the same with one server and silently wrong
    // with two. They now live in that server's `options`.
    //
    // Reads fall back to the old global key, and writes update both. That is the whole
    // migration: an install that has never touched the setting keeps answering from the
    // global key, and the first write moves it across. No one-shot upgrade pass, so
    // there is no version to get wrong and nothing to re-run if it half-fails.

    /** The configured Music Assistant server, or null when there isn't one. */
    val maServer: Flow<ServerConfig?> =
        servers.map { list -> list.firstOrNull { it.kind == ServerKind.MUSIC_ASSISTANT } }

    private fun maOption(
        prefs: androidx.datastore.preferences.core.Preferences,
        key: String,
    ): String? = storedServers(prefs)
        .firstOrNull { it.kind == ServerKind.MUSIC_ASSISTANT }
        ?.option(key)

    /**
     * Write [key] onto the MA server's options.
     *
     * A no-op when no MA server is configured — the global key written alongside is
     * then the only copy, and it becomes the seed for the server's options as soon as
     * one is added.
     */
    private fun putMaOption(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        key: String,
        value: String,
    ) {
        val list = storedServers(prefs)
        if (list.none { it.kind == ServerKind.MUSIC_ASSISTANT }) return
        prefs[SERVERS] = encodeServers(
            list.map { if (it.kind == ServerKind.MUSIC_ASSISTANT) it.withOption(key, value) else it },
        )
    }

    /**
     * Replace the whole list. Callers add, edit and remove by transforming the list
     * they already collected — there is no partial write, because a rename and a
     * credential change are the same operation to the store and splitting them would
     * only invite two writes racing.
     */
    suspend fun saveServers(list: List<ServerConfig>) {
        context.dataStore.edit { prefs ->
            prefs[SERVERS] = encodeServers(list)
            mirrorLegacyKeys(prefs, list, resolveActiveId(prefs, list))
        }
    }

    suspend fun setActiveServer(id: String) {
        context.dataStore.edit { prefs ->
            prefs[ACTIVE_SERVER] = id
            val list = storedServers(prefs)
            mirrorLegacyKeys(prefs, list, id)
        }
    }

    /**
     * Restate the old fixed keys from the list.
     *
     * Everything that has not been ported to the server list keeps reading these, so
     * they have to stay correct rather than merely present. A kind with no legacy
     * equivalent — Jellyfin, and everything after it — mirrors as `subsonic`, because
     * what the old key really encoded was "the app plays this itself", and that is
     * exactly what the rest of the app uses it to decide.
     */
    private fun mirrorLegacyKeys(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        list: List<ServerConfig>,
        activeId: String?,
    ) {
        val active = list.firstOrNull { it.id == activeId } ?: list.firstOrNull()
        prefs[BACKEND] = if (active?.kind?.playsLocally == false) BACKEND_MA else BACKEND_SUBSONIC

        // Written *and cleared*. A mirror that only ever writes is a mirror that lies
        // after a removal: deleting the Navidrome server left its address on file, and
        // the next launch cheerfully connected to a server the user had taken off the
        // list.
        val ma = list.firstOrNull { it.kind == ServerKind.MUSIC_ASSISTANT }
        prefs[MA_BASE_URL] = ma?.url.orEmpty()
        prefs[MA_USERNAME] = ma?.username.orEmpty()
        prefs[MA_PASSWORD] = Crypto.encrypt(ma?.password.orEmpty())

        // The Navidrome keys back downloads and "play at original quality", which work
        // from either backend — so they follow the first Subsonic-speaking server in
        // the list rather than the active one.
        val nav = list.firstOrNull { it.kind == ServerKind.NAVIDROME || it.kind == ServerKind.SUBSONIC }
        prefs[NAV_URL] = nav?.url.orEmpty()
        prefs[NAV_USERNAME] = nav?.username.orEmpty()
        prefs[NAV_PASSWORD] = Crypto.encrypt(nav?.password.orEmpty())
        prefs[NAV_STREAM_FORMAT] = nav?.option(ServerConfig.OPT_STREAM_FORMAT) ?: "raw"
    }

    /**
     * The list an install that predates it should start with.
     *
     * Derived rather than written on first run: a migration that writes on read
     * races every other reader, and there is nothing here that cannot be recomputed.
     * The first real edit persists it. A slot with no address is left out — an empty
     * "Navidrome" card in Settings would look like something had gone wrong.
     */
    private fun legacyServers(
        prefs: androidx.datastore.preferences.core.Preferences,
    ): List<ServerConfig> = buildList {
        prefs[MA_BASE_URL]?.takeIf { it.isNotBlank() }?.let {
            add(
                ServerConfig(
                    id = LEGACY_MA_ID,
                    kind = ServerKind.MUSIC_ASSISTANT,
                    url = it,
                    username = prefs[MA_USERNAME].orEmpty(),
                    password = Crypto.decrypt(prefs[MA_PASSWORD] ?: ""),
                )
            )
        }
        prefs[NAV_URL]?.takeIf { it.isNotBlank() }?.let {
            add(
                ServerConfig(
                    id = LEGACY_NAV_ID,
                    kind = ServerKind.NAVIDROME,
                    url = it,
                    username = prefs[NAV_USERNAME].orEmpty(),
                    password = Crypto.decrypt(prefs[NAV_PASSWORD] ?: ""),
                    options = mapOf(
                        ServerConfig.OPT_STREAM_FORMAT to (prefs[NAV_STREAM_FORMAT] ?: "raw"),
                    ),
                )
            )
        }
    }

    private fun encodeServers(list: List<ServerConfig>): String =
        serverJson.encodeToString(
            ListSerializer(ServerConfig.serializer()),
            list.map { it.copy(password = Crypto.encrypt(it.password), token = Crypto.encrypt(it.token)) },
        )

    /**
     * The stored list, or **null** when it could not be read.
     *
     * Null rather than an empty list, and the distinction is the whole point: an empty
     * list is a real answer that [mirrorLegacyKeys] acts on by blanking every stored
     * credential. Returning one for a decode failure turned a recoverable read error
     * into permanent loss of the addresses and passwords — the caller falls back to
     * the legacy keys instead, which is exactly the data that would otherwise be
     * destroyed.
     */
    private fun decodeServers(raw: String): List<ServerConfig>? = runCatching {
        serverJson.decodeFromString(ListSerializer(ServerConfig.serializer()), raw)
            .map { it.copy(password = Crypto.decrypt(it.password), token = Crypto.decrypt(it.token)) }
    }.getOrNull()

    // ── Settings export / import ────────────────────────────────────────────
    //
    // Every stored key, as portable JSON encrypted with a user passphrase rather
    // than the on-device Keystore key [Crypto] otherwise uses — that key is
    // deliberately non-exportable, so writing it straight to a file would produce
    // a blob unreadable on any other device (or after a reinstall). Individually-
    // encrypted legacy fields are decrypted first and the *whole* resulting JSON
    // is what gets encrypted, so nothing here ever touches disk unencrypted.

    /** Every setting, as a password-encrypted portable blob. Null if encryption fails. */
    suspend fun exportSettings(password: String): String? {
        val prefs = context.dataStore.data.first()
        val obj = buildJsonObject {
            prefs.asMap().forEach { (key, value) ->
                if (key.name == SERVERS.name) return@forEach   // handled below, decrypted properly
                when (val v = if (key.name in ENCRYPTED_KEY_NAMES) Crypto.decrypt(value as? String ?: "") else value) {
                    is String -> put(key.name, JsonPrimitive(v))
                    is Boolean -> put(key.name, JsonPrimitive(v))
                    // This app has never stored an Int/Float/Long/Set<String> preference;
                    // numeric settings are stringPreferencesKey. Nothing to handle here.
                    else -> Unit
                }
            }
            put(SERVERS_EXPORT_KEY, JsonPrimitive(serverJson.encodeToString(ListSerializer(ServerConfig.serializer()), storedServers(prefs))))
        }
        return PortableCrypto.encrypt(obj.toString(), password)
    }

    /**
     * Restore settings from an [exportSettings] blob.
     *
     * Server credentials and the individually-encrypted legacy keys are
     * re-encrypted under *this* device's Keystore key on the way back in — the
     * password-derived encryption the file carries never itself reaches disk.
     *
     * @return true on success; false on a wrong password, or a blob that isn't
     *   one of these exports at all.
     */
    suspend fun importSettings(blob: String, password: String): Boolean {
        val json = PortableCrypto.decrypt(blob, password) ?: return false
        val obj = try {
            Json.parseToJsonElement(json) as? JsonObject ?: return false
        } catch (e: Exception) {
            return false
        }
        context.dataStore.edit { prefs ->
            obj.forEach { (name, element) ->
                if (name == SERVERS_EXPORT_KEY) return@forEach
                val primitive = element as? JsonPrimitive ?: return@forEach
                val bool = primitive.booleanOrNull
                if (bool != null) {
                    prefs[booleanPreferencesKey(name)] = bool
                } else {
                    val str = primitive.content
                    prefs[stringPreferencesKey(name)] = if (name in ENCRYPTED_KEY_NAMES) Crypto.encrypt(str) else str
                }
            }
            (obj[SERVERS_EXPORT_KEY] as? JsonPrimitive)?.let { serversEl ->
                val list = serverJson.decodeFromString(ListSerializer(ServerConfig.serializer()), serversEl.content)
                prefs[SERVERS] = encodeServers(list)
                mirrorLegacyKeys(prefs, list, resolveActiveId(prefs, list))
            }
        }
        return true
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
    val playerName: Flow<String> = context.dataStore.data.map {
        maOption(it, ServerConfig.OPT_PLAYER_NAME) ?: it[PLAYER_NAME] ?: ""
    }
    val targetPlayer: Flow<String> = context.dataStore.data.map {
        maOption(it, ServerConfig.OPT_TARGET_PLAYER) ?: it[TARGET_PLAYER] ?: ""
    }
    val nowPlayingLayout: Flow<String> = context.dataStore.data.map { it[NOW_PLAYING_LAYOUT] ?: "tab" }
    /** How the Now Playing seek bar is drawn — a straight line, or a wobbling wave. */
    val seekBarStyle: Flow<String> = context.dataStore.data.map { it[SEEK_BAR_STYLE] ?: "line" }
    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { it[ONBOARDING_COMPLETED] ?: false }
    val theme: Flow<String> = context.dataStore.data.map { it[THEME] ?: "oled" }
    val accentSource: Flow<String> = context.dataStore.data.map { it[ACCENT_SOURCE] ?: "album" }
    /** Stored as an ARGB hex string; empty means "use the built-in amber". */
    val fixedAccent: Flow<String> = context.dataStore.data.map { it[FIXED_ACCENT] ?: "" }
    val preferHiRes: Flow<Boolean> = context.dataStore.data.map {
        maOption(it, ServerConfig.OPT_PREFER_HI_RES)?.toBooleanStrictOrNull() ?: it[PREFER_HI_RES] ?: true
    }
    val preferFlac: Flow<Boolean> = context.dataStore.data.map {
        maOption(it, ServerConfig.OPT_PREFER_FLAC)?.toBooleanStrictOrNull() ?: it[PREFER_FLAC] ?: true
    }
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

    val sendspinCodec: Flow<String> = context.dataStore.data.map {
        maOption(it, ServerConfig.OPT_SENDSPIN_CODEC) ?: it[SENDSPIN_CODEC] ?: "auto"
    }

    /**
     * What Navidrome should send for a direct stream. "raw" is the stored file
     * untouched; anything else asks the server to transcode, which is worth it on a
     * slow connection and wasteful on a fast one.
     */
    val navStreamFormat: Flow<String> = context.dataStore.data.map { it[NAV_STREAM_FORMAT] ?: "raw" }

    /**
     * Whether to request 24-bit bit-perfect playback from the AudioTrack path.
     * When true and the device supports `ENCODING_PCM_24BIT_PACKED` (API 31+,
     * which is our minSdk), [SendspinExoEngine] builds a 24-bit AudioTrack
     * instead of truncating to 16-bit. The server must also be sending 24-bit
     * — see [FormatNegotiator.MAX_BIT_DEPTH], which is now 24.
     */
    val bitPerfect24Bit: Flow<Boolean> = context.dataStore.data.map { it[BIT_PERFECT] ?: false }

    /**
     * Experimental: routes decoded MA audio through the native Oboe engine
     * ([com.engabd.sendpin.audio.SendspinNativeOutput]) instead of the platform
     * `AudioTrack`. Only 16-bit PCM is supported - combining this with
     * [bitPerfect24Bit] is a known gap, not yet handled.
     *
     * Off by default and staying that way for now: this path is silent on device.
     * The stream opens and starts cleanly, `buffered == written` on every stall so
     * not one frame is ever consumed, and ExoPlayer eventually gives up with
     * `Player stuck playing with no progress`. Three genuine defects have been
     * fixed along the way and none of them was this one — see
     * `docs/oboe-investigation.md`.
     *
     * The `useExoPlayerForSendspin` companion to this is gone: the ExoPlayer engine
     * is no longer optional, it is the only MA engine. See
     * [com.engabd.sendpin.service.Playback.startSendspin].
     */
    val useOboeOutput: Flow<Boolean> = context.dataStore.data.map { it[SENDSPIN_OBOE] ?: false }

    /**
     * The system AudioDeviceInfo ID to route audio to, for USB DAC support.
     * Empty string means "let the system pick" (default speaker/headset).
     * Set by [com.engabd.sendpin.service.Playback] when a USB audio device is
     * detected; consumed by both [SendspinExoEngine] and [LocalPlayer].
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
    val staticDelayMs: Flow<Int> = context.dataStore.data.map {
        maOption(it, ServerConfig.OPT_STATIC_DELAY_MS)?.toIntOrNull()
            ?: it[STATIC_DELAY_MS]?.toIntOrNull() ?: 0
    }

    suspend fun setStaticDelayMs(ms: Int) = context.dataStore.edit {
        val v = ms.coerceIn(-MAX_TRIM_MS, MAX_TRIM_MS).toString()
        it[STATIC_DELAY_MS] = v
        putMaOption(it, ServerConfig.OPT_STATIC_DELAY_MS, v)
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

    /**
     * Manual trim on synced lyrics, in milliseconds. Positive means the words are
     * arriving late and should be pulled forward.
     *
     * Providers disagree by a beat or two — the same track's LRC can be stamped
     * against a different master, or carry an offset tag nobody applied — and there is
     * no way to know which is right from here. So it is the listener's dial.
     */
    val lyricsOffsetMs: Flow<Int> = context.dataStore.data.map { it[LYRICS_OFFSET_MS]?.toIntOrNull() ?: 0 }

    suspend fun setLyricsOffsetMs(ms: Int) = context.dataStore.edit {
        it[LYRICS_OFFSET_MS] = ms.coerceIn(-MAX_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS).toString()
    }

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
    val keepAliveForAnnouncements: Flow<Boolean> = context.dataStore.data.map {
        maOption(it, ServerConfig.OPT_KEEP_ALIVE)?.toBooleanStrictOrNull()
            ?: it[KEEP_ALIVE_ANNOUNCEMENTS] ?: true
    }

    suspend fun setKeepAliveForAnnouncements(value: Boolean) {
        context.dataStore.edit {
            it[KEEP_ALIVE_ANNOUNCEMENTS] = value
            putMaOption(it, ServerConfig.OPT_KEEP_ALIVE, value.toString())
        }
    }

    /**
     * Radio mode: MA keeps generating similar tracks once the queue runs out.
     * Persisted rather than held in a view model because it is applied when
     * playback *starts* (`player_queues/play_media`), which the library does,
     * while the toggle that sets it lives on Now Playing.
     */
    val radioMode: Flow<Boolean> = context.dataStore.data.map { it[RADIO_MODE] ?: false }

    /**
     * Seconds of fade between tracks on the local player. 0 — the default — is
     * gapless, which is what an album wants.
     *
     * Not a crossfade: one ExoPlayer has one output, so two tracks cannot overlap
     * through it. This fades one out and the next in, which is what a party playlist
     * is after; a true overlap needs a second player and is its own piece of work.
     * Suppressed automatically when the queue is a single album.
     */
    val navFadeSeconds: Flow<Int> = context.dataStore.data.map { it[NAV_FADE_SECONDS]?.toIntOrNull() ?: 0 }

    suspend fun setNavFadeSeconds(value: Int) = context.dataStore.edit {
        it[NAV_FADE_SECONDS] = value.coerceIn(0, 12).toString()
    }

    /**
     * Time [navFadeSeconds]' fade to land on a beat rather than an arbitrary N
     * seconds before the end — see [BeatAlignedFade]. Off by default: it needs a
     * track scan to do anything, and the first play of an unscanned track falls
     * back to the fixed window silently either way, so there's nothing lost by
     * defaulting it off beyond the listener not knowing to turn it on.
     */
    val beatMatchedCrossfade: Flow<Boolean> = context.dataStore.data.map { it[BEAT_MATCHED_CROSSFADE] ?: false }

    suspend fun setBeatMatchedCrossfade(value: Boolean) = context.dataStore.edit {
        it[BEAT_MATCHED_CROSSFADE] = value
    }

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
        context.dataStore.edit { it[PLAYER_NAME] = name; putMaOption(it, ServerConfig.OPT_PLAYER_NAME, name) }
    }

    suspend fun setTargetPlayer(playerId: String) {
        context.dataStore.edit {
            it[TARGET_PLAYER] = playerId
            putMaOption(it, ServerConfig.OPT_TARGET_PLAYER, playerId)
        }
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

    suspend fun setSeekBarStyle(style: String) {
        context.dataStore.edit { it[SEEK_BAR_STYLE] = style }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        bootPrefs.edit().putBoolean("onboarded", completed).apply()
        context.dataStore.edit { it[ONBOARDING_COMPLETED] = completed }
    }

    /**
     * Whether the user skipped setup rather than finishing it.
     *
     * The wizard's `finish(skipped)` took this and ignored it, so both paths wrote the
     * same thing and nothing downstream could tell a deliberate "no server, thanks"
     * from an interrupted setup.
     */
    val onboardingSkipped: Flow<Boolean> = context.dataStore.data.map { it[ONBOARDING_SKIPPED] ?: false }

    suspend fun setOnboardingSkipped(skipped: Boolean) {
        context.dataStore.edit { it[ONBOARDING_SKIPPED] = skipped }
    }

    /**
     * Synchronous mirror of [onboardingCompleted], for `MainActivity.onCreate`.
     *
     * Same reason the theme has one: the decision is needed before the first frame, and
     * DataStore is asynchronous by design. What it decides here is whether to put a
     * permission dialog in front of someone who has not yet seen the app.
     */
    val hasCompletedOnboarding: Boolean get() = bootPrefs.getBoolean("onboarded", false)

    /** Takes effect on the next connect — the format list is sent in the hello. */
    suspend fun setPreferHiRes(value: Boolean) {
        context.dataStore.edit {
            it[PREFER_HI_RES] = value
            putMaOption(it, ServerConfig.OPT_PREFER_HI_RES, value.toString())
        }
    }

    suspend fun setPreferFlac(value: Boolean) {
        context.dataStore.edit {
            it[PREFER_FLAC] = value
            putMaOption(it, ServerConfig.OPT_PREFER_FLAC, value.toString())
        }
    }

    suspend fun setPreferOriginal(value: Boolean) {
        context.dataStore.edit { it[PREFER_ORIGINAL] = value }
    }

    suspend fun setRegisteredPlayerName(name: String) {
        context.dataStore.edit { it[REGISTERED_NAME] = name }
    }

    /** Takes effect on the next connect — the format list is sent in the hello. */
    suspend fun setSendspinCodec(value: String) {
        context.dataStore.edit {
            it[SENDSPIN_CODEC] = value
            putMaOption(it, ServerConfig.OPT_SENDSPIN_CODEC, value)
        }
    }

    /** Applies to the next track: the format is a query parameter on the stream URL. */
    suspend fun setNavStreamFormat(value: String) {
        context.dataStore.edit { it[NAV_STREAM_FORMAT] = value }
    }

    /** Takes effect on the next stream/start — the AudioTrack is built per stream. */
    suspend fun setBitPerfect24Bit(value: Boolean) {
        // Mirrored into the synchronous store for the same reason the theme is: the
        // local player has to know before it builds its renderers, and DataStore is a
        // Flow. See [bootBitPerfect].
        bootPrefs.edit().putBoolean("bit_perfect", value).apply()
        context.dataStore.edit { it[BIT_PERFECT] = value }
    }

    /** Takes effect on the next `connectToServer()` — see [useOboeOutput]. */
    suspend fun setUseOboeOutput(value: Boolean) {
        context.dataStore.edit { it[SENDSPIN_OBOE] = value }
    }

    /**
     * Bit-perfect, readable without a coroutine.
     *
     * ExoPlayer's float output is a *renderer factory* setting, fixed when the player
     * is constructed — there is no per-track switch. So the local player needs the
     * answer synchronously at build time, which is what this is for.
     */
    val bootBitPerfect: Boolean get() = bootPrefs.getBoolean("bit_perfect", false)

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

    /**
     * The paired bridge's id. The bridge's TLS certificate carries this as its
     * Subject Common Name, and checking it is how the app knows it is talking to
     * *that* bridge — ordinary hostname verification cannot apply when the
     * connection is made to an IP address.
     */
    val hueBridgeId: Flow<String> = context.dataStore.data.map { it[HUE_BRIDGE_ID] ?: "" }

    /** Which Light Sync transport: "ha" (Home Assistant) or "direct" (Hue Bridge). */
    val lightSyncMode: Flow<String> = context.dataStore.data.map { it[LIGHT_SYNC_MODE] ?: "ha" }

    /**
     * Rungs Auto is allowed to pick between.
     *
     * A palette, not a range: the ladder is rescaled onto whatever is enabled
     * rather than clipped, so a heavy track still reaches the top of a narrow
     * selection. Defaults to the calmer three, matching syncoV2.
     */
    val lightSyncAutoLevels: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[LIGHT_SYNC_AUTO_LEVELS]
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("subtle", "medium", "high")
    }

    /** Whether [lightSyncMode] follows the selected library. See the key's docs. */
    val lightSyncModeAuto: Flow<Boolean> =
        context.dataStore.data.map { it[LIGHT_SYNC_MODE_AUTO] ?: true }

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

    /** Whether advanced tunables are shown on the direct Light Sync screen. */
    val lightSyncAdvanced: Flow<Boolean> = context.dataStore.data.map { it[LIGHT_SYNC_ADVANCED] ?: false }

    /**
     * Direct-mode advanced tunables. Keys are the syncoV2 tunable names;
     * missing keys default to 1.0 (no change). Values are coerced to 0..2.
     */
    val lightSyncTunables: Flow<Map<String, Float>> = context.dataStore.data.map { prefs ->
        val raw = prefs[LIGHT_SYNC_TUNABLES]
        if (raw.isNullOrBlank()) return@map emptyMap()
        try {
            val obj = serverJson.decodeFromString(JsonObject.serializer(), raw)
            obj.mapNotNull { (k, v) ->
                val f = v.jsonPrimitive.floatOrNull ?: v.jsonPrimitive.content.toFloatOrNull()
                if (f != null && com.engabd.sendpin.hue.SyncoEngine.TUNABLE_KEYS.contains(k)) k to f.coerceIn(0f, 2f)
                else null
            }.toMap()
        } catch (_: Exception) { emptyMap() }
    }

    /** Analyse tracks ahead of the show. See the key's docs for the default. */
    val lightSyncPrescan: Flow<Boolean> = context.dataStore.data.map { it[LIGHT_SYNC_PRESCAN] ?: true }

    /** Only pull remote tracks down for analysis on an unmetered network. */
    val lightSyncPrescanWifiOnly: Flow<Boolean> =
        context.dataStore.data.map { it[LIGHT_SYNC_PRESCAN_WIFI] ?: true }

    /** Room gestures. Off unless asked for — see the key's docs. */
    val lightSyncSpatial: Flow<Boolean> =
        context.dataStore.data.map { it[LIGHT_SYNC_SPATIAL] ?: false }

    /** Layer 1 — a deterministic per-track visual fingerprint. Off by default. */
    val musicDnaEnabled: Flow<Boolean> = context.dataStore.data.map { it[MUSIC_DNA_ENABLED] ?: false }

    /** Layer 2 — colour temperature follows the song's live structure. Off by default. */
    val emotionalArcEnabled: Flow<Boolean> = context.dataStore.data.map { it[EMOTIONAL_ARC_ENABLED] ?: false }

    /** Layer 3 — instrument groups at fixed positions in the room. Off by default. */
    val phantomStageEnabled: Flow<Boolean> = context.dataStore.data.map { it[PHANTOM_STAGE_ENABLED] ?: false }

    /** Layer 4 — phone motion as a lighting controller. Off by default. */
    val phoneConductorEnabled: Flow<Boolean> = context.dataStore.data.map { it[PHONE_CONDUCTOR_ENABLED] ?: false }

    suspend fun setHueBridge(
        ip: String,
        appKey: String,
        clientKey: String,
        appId: String = "",
        bridgeId: String = "",
    ) {
        context.dataStore.edit {
            it[HUE_BRIDGE_IP] = ip
            it[HUE_APP_KEY] = Crypto.encrypt(appKey)
            it[HUE_CLIENT_KEY] = Crypto.encrypt(clientKey)
            // Cleared along with the rest on unpair, rather than only written
            // when non-blank: a stale id or application id left behind would be
            // checked against the *next* bridge paired.
            it[HUE_APP_ID] = appId
            it[HUE_BRIDGE_ID] = bridgeId
        }
    }

    suspend fun setHueConfigId(id: String) {
        context.dataStore.edit { it[HUE_CONFIG_ID] = id }
    }

    /**
     * Set the transport. [manual] marks this as the user's own choice and stops
     * the library from overriding it; the automatic coordinator passes false.
     */
    suspend fun setLightSyncMode(mode: String, manual: Boolean = false) {
        context.dataStore.edit {
            it[LIGHT_SYNC_MODE] = mode
            if (manual) it[LIGHT_SYNC_MODE_AUTO] = false
        }
    }

    suspend fun setLightSyncAutoLevels(levels: List<String>) {
        context.dataStore.edit { it[LIGHT_SYNC_AUTO_LEVELS] = levels.joinToString(",") }
    }

    /** Hand control of the transport back to the library selection. */
    suspend fun setLightSyncModeAuto(auto: Boolean) {
        context.dataStore.edit { it[LIGHT_SYNC_MODE_AUTO] = auto }
    }

    // ── Driving mode ─────────────────────────────────────────────────────────
    //
    // A phone in a cradle running Maps, and changing a track meaning: leave the map,
    // find the app, hit a small target, go back. That is the one genuinely *unsafe*
    // gap in this app, so the controls are large, few, and reachable without leaving
    // whatever is on screen.

    /** The feature is switched on at all. Off by default — it asks for permissions. */
    val drivingEnabled: Flow<Boolean> = context.dataStore.data.map { it[DRIVING_ENABLED] ?: false }

    /**
     * Which window mechanism the bar uses.
     *
     * [DRIVING_PIP] costs no permission at all and is therefore the default: a
     * feature someone sets up once in a car park should not open with a Settings
     * trip. It buys a small fixed window, a capped number of actions, and the rule
     * that the activity must be foreground at the moment it enters — so the flow is
     * "open the app, then start navigating".
     *
     * [DRIVING_OVERLAY] is the one that gives genuinely driving-sized targets and
     * free positioning, and costs `SYSTEM_ALERT_WINDOW`.
     */
    val drivingMechanism: Flow<String> =
        context.dataStore.data.map { it[DRIVING_MECHANISM] ?: DRIVING_PIP }

    /**
     * The bonded Bluetooth device the user nominated as their car stereo, or blank.
     *
     * The trigger is deliberately *not* "is Google Maps in front". Reading the
     * foreground app needs either `PACKAGE_USAGE_STATS` or an `AccessibilityService`
     * — a Settings-screen grant or the most policy-sensitive permission on the
     * platform — and the requirement is "control music without leaving the map", not
     * "know that Maps is running". Connecting to the car is the same situation and
     * costs one runtime permission.
     */
    val drivingCarAddress: Flow<String> = context.dataStore.data.map { it[DRIVING_CAR_ADDRESS] ?: "" }
    val drivingCarName: Flow<String> = context.dataStore.data.map { it[DRIVING_CAR_NAME] ?: "" }

    /**
     * Auto-pause playback while the phone is ringing or on a call. Off by default:
     * it needs `READ_PHONE_STATE`, requested at runtime only when this is turned on
     * — the same "don't ask until it's wanted" rule the driving-car picker follows.
     */
    val pauseForCalls: Flow<Boolean> = context.dataStore.data.map { it[PAUSE_FOR_CALLS] ?: false }

    /**
     * GPS speed-limit alert. Off by default and needs `ACCESS_FINE_LOCATION`,
     * requested at runtime only when turned on — see [SpeedMonitor].
     */
    val speedLimitAlertEnabled: Flow<Boolean> = context.dataStore.data.map { it[SPEED_LIMIT_ALERT_ENABLED] ?: false }

    /** 0 means "not set" — [SpeedMonitor] treats that as "nothing to alert on". */
    val drivingSpeedLimitKmh: Flow<Int> = context.dataStore.data.map { it[DRIVING_SPEED_LIMIT_KMH]?.toIntOrNull() ?: 0 }

    /** How far over [drivingSpeedLimitKmh] before the alert fires. Default 5%, per SpeedAlert's own doc. */
    val drivingSpeedTolerancePct: Flow<Int> = context.dataStore.data.map { it[DRIVING_SPEED_TOLERANCE_PCT]?.toIntOrNull() ?: 5 }

    /** Speed-adaptive volume boost. Off by default; shares [SpeedMonitor] with the alert above. */
    val speedAdaptiveVolume: Flow<Boolean> = context.dataStore.data.map { it[SPEED_ADAPTIVE_VOLUME] ?: false }

    suspend fun setSpeedLimitAlertEnabled(on: Boolean) = context.dataStore.edit { it[SPEED_LIMIT_ALERT_ENABLED] = on }

    suspend fun setDrivingSpeedLimitKmh(value: Int) = context.dataStore.edit {
        it[DRIVING_SPEED_LIMIT_KMH] = value.coerceIn(0, 300).toString()
    }

    suspend fun setDrivingSpeedTolerancePct(value: Int) = context.dataStore.edit {
        it[DRIVING_SPEED_TOLERANCE_PCT] = value.coerceIn(0, 25).toString()
    }

    suspend fun setSpeedAdaptiveVolume(on: Boolean) = context.dataStore.edit { it[SPEED_ADAPTIVE_VOLUME] = on }

    suspend fun setDrivingEnabled(on: Boolean) {
        context.dataStore.edit { it[DRIVING_ENABLED] = on }
    }

    suspend fun setDrivingMechanism(value: String) {
        context.dataStore.edit { it[DRIVING_MECHANISM] = value }
    }

    suspend fun setDrivingCar(address: String, name: String) {
        context.dataStore.edit {
            it[DRIVING_CAR_ADDRESS] = address
            it[DRIVING_CAR_NAME] = name
        }
    }

    suspend fun setPauseForCalls(on: Boolean) {
        context.dataStore.edit { it[PAUSE_FOR_CALLS] = on }
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

    suspend fun setLightSyncAdvanced(on: Boolean) {
        context.dataStore.edit { it[LIGHT_SYNC_ADVANCED] = on }
    }

    suspend fun setLightSyncTunables(tunables: Map<String, Float>) {
        val obj = kotlinx.serialization.json.buildJsonObject {
            for ((k, v) in tunables) {
                if (com.engabd.sendpin.hue.SyncoEngine.TUNABLE_KEYS.contains(k)) put(k, kotlinx.serialization.json.JsonPrimitive(v.coerceIn(0f, 2f)))
            }
        }
        context.dataStore.edit { it[LIGHT_SYNC_TUNABLES] = serverJson.encodeToString(JsonObject.serializer(), obj) }
    }

    suspend fun setLightSyncPrescan(on: Boolean) {
        context.dataStore.edit { it[LIGHT_SYNC_PRESCAN] = on }
    }

    suspend fun setLightSyncPrescanWifiOnly(on: Boolean) {
        context.dataStore.edit { it[LIGHT_SYNC_PRESCAN_WIFI] = on }
    }

    suspend fun setLightSyncSpatial(on: Boolean) {
        context.dataStore.edit { it[LIGHT_SYNC_SPATIAL] = on }
    }

    suspend fun setMusicDnaEnabled(on: Boolean) {
        context.dataStore.edit { it[MUSIC_DNA_ENABLED] = on }
    }

    suspend fun setEmotionalArcEnabled(on: Boolean) {
        context.dataStore.edit { it[EMOTIONAL_ARC_ENABLED] = on }
    }

    suspend fun setPhantomStageEnabled(on: Boolean) {
        context.dataStore.edit { it[PHANTOM_STAGE_ENABLED] = on }
    }

    suspend fun setPhoneConductorEnabled(on: Boolean) {
        context.dataStore.edit { it[PHONE_CONDUCTOR_ENABLED] = on }
    }

    // ── Crash reporting ────────────────────────────────────────────────────

    /** GitHub repo in owner/repo form, e.g. "engabd11/CAMusic". */
    val crashGitHubRepo: Flow<String> = context.dataStore.data.map { it[CRASH_GITHUB_REPO] ?: "engabd11/CAMusic" }

    /** Encrypted personal access token for automatic GitHub issue creation. */
    val crashGitHubToken: Flow<String> = context.dataStore.data.map { Crypto.decrypt(it[CRASH_GITHUB_TOKEN] ?: "") }

    /** Whether to attempt an automatic GitHub issue on crash. */
    val crashAutoUpload: Flow<Boolean> = context.dataStore.data.map { it[CRASH_AUTO_UPLOAD] ?: false }

    suspend fun setCrashGitHubRepo(repo: String) {
        context.dataStore.edit { it[CRASH_GITHUB_REPO] = repo.trim() }
    }

    suspend fun setCrashGitHubToken(token: String) {
        context.dataStore.edit { it[CRASH_GITHUB_TOKEN] = Crypto.encrypt(token.trim()) }
    }

    suspend fun setCrashAutoUpload(auto: Boolean) {
        context.dataStore.edit { it[CRASH_AUTO_UPLOAD] = auto }
    }

}
