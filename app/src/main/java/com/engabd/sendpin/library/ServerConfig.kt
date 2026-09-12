package com.engabd.sendpin.library

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A kind of server the app can browse a music library from.
 *
 * Everything here except [MUSIC_ASSISTANT] is a *library the app plays itself*: it
 * hands out a stream URL, this phone decodes it, and the Light Sync tap can see it.
 * Music Assistant is the odd one out — it owns a server-side queue and plays to
 * speakers this app never touches — which is why it is a separate mode rather than
 * another [MusicSource]. See that interface for the line between them.
 *
 * [supported] is the whole point of listing the unbuilt ones. A settings screen that
 * shows only what exists says nothing about where this is going, and every one of
 * these has been asked for; showing them greyed with a reason is an honest roadmap in
 * the one place someone goes looking. `docs/providers.md` carries the adapter recipe
 * and each one's auth and endpoints.
 */
@Serializable
enum class ServerKind(
    val label: String,
    /** One line, for the picker row. Says what it is, not how it works. */
    val blurb: String,
    val supported: Boolean = false,
    /**
     * Addable, but young: the adapter works and the picker offers it, under its own
     * heading and with the tag on the row. These are the reverse-engineered
     * streaming services — no official third-party API exists for any of them, so
     * they are the kinds most likely to break when a provider changes its wire
     * format, and the honest thing is to say so where the user chooses one rather
     * than after it fails.
     *
     * Orthogonal to [supported]: this says how settled a kind is, not whether it
     * can be built. A kind that is [supported] and experimental is offered; one
     * that is neither is on the roadmap.
     */
    val experimental: Boolean = false,
    /** Roughly what a URL for this looks like, for the address field's placeholder. */
    val urlHint: String = "",
    /** How the user proves who they are — decides which fields the form shows. */
    val auth: AuthStyle = AuthStyle.USER_PASSWORD,
    /**
     * True for a streaming **account** rather than a server: the kind has a form
     * (credentials) but no host to point at, because the service's address is
     * fixed and well-known. Drives [hasAddress]; the cloud drives in the planned
     * list are the same shape of thing and should take this when their adapters
     * are built.
     */
    val cloudAccount: Boolean = false,
) {
    MUSIC_ASSISTANT(
        "Music Assistant",
        "Plays to any speaker on the network, with grouping and a server-side queue.",
        supported = true,
        urlHint = "http://192.168.0.10:8095",
        auth = AuthStyle.OPTIONAL_USER_PASSWORD,
    ),
    NAVIDROME(
        "Navidrome",
        "A fast self-hosted server with the OpenSubsonic extensions, so lyrics, ReplayGain and exact formats.",
        supported = true,
        urlHint = "http://192.168.0.10:4533",
    ),
    SUBSONIC(
        "Subsonic-compatible",
        "Any Subsonic or OpenSubsonic server: Gonic, Airsonic, Astiga, Ampache, Funkwhale or epoupon's LMS.",
        supported = true,
        urlHint = "http://192.168.0.10:4040",
    ),
    JELLYFIN(
        "Jellyfin",
        "The open-source media server. Browses its music library and streams the original file.",
        supported = true,
        urlHint = "http://192.168.0.10:8096",
    ),
    EMBY(
        "Emby",
        "Jellyfin's ancestor, with a near-identical API and its own auth header.",
        supported = true,
        urlHint = "http://192.168.0.10:8096",
    ),
    PLEX(
        "Plex",
        "Needs the plex.tv PIN sign-in rather than a server password.",
        supported = true,
        urlHint = "http://192.168.0.10:32400",
        auth = AuthStyle.LINKED_ACCOUNT,
    ),

    MPD(
        "MPD",
        "Music Player Daemon. moOde, Volumio, piCorePlayer and Mopidy are MPD underneath. " +
            "Playback stays on the DAC box; the phone browses and controls.",
        supported = true,
        urlHint = "http://192.168.0.10:6600",
        auth = AuthStyle.OPTIONAL_USER_PASSWORD,
    ),
    FOOBAR2000(
        "foobar2000",
        "Desktop player with the Beefweb plugin. Browses its library and drives its transport over REST.",
        supported = true,
        urlHint = "http://192.168.0.10:8880",
        auth = AuthStyle.OPTIONAL_USER_PASSWORD,
    ),

    // ── Direct streaming. Accounts, not servers: credentials, no address. ────
    // No streaming service offers an official API a third-party player can stream
    // from, so each arrives the way Music Assistant talks to them: an embedded
    // client or the service's own undocumented endpoints. That is what keeps them
    // flagged [experimental] now that their sources have landed — they work, and
    // they are the first things a provider's next wire change will break.
    // YouTube Music is deliberately absent — no viable path (OAuth withdrawn,
    // cookie + PO-token machinery, playback capture blocked on Android).
    SPOTIFY(
        "Spotify",
        "Your Premium account, played by this phone through an embedded Spotify client. Light sync included.",
        supported = true,
        experimental = true,
        auth = AuthStyle.USER_PASSWORD,
        cloudAccount = true,
    ),
    QOBUZ(
        "Qobuz",
        "Streaming in FLAC up to 24 bit / 192 kHz, straight from your Qobuz subscription.",
        supported = true,
        experimental = true,
        auth = AuthStyle.USER_PASSWORD,
        cloudAccount = true,
    ),
    TIDAL(
        "Tidal",
        "Your Tidal library, signed in on the provider's own page.",
        supported = true,
        experimental = true,
        auth = AuthStyle.LINKED_ACCOUNT,
        cloudAccount = true,
    ),

    // ── Planned. Listed so the roadmap is visible where it is asked about. ──
    AUDIOBOOKSHELF(
        "Audiobookshelf",
        "Audiobooks and podcasts, with a music library alongside them.",
        urlHint = "http://192.168.0.10:13378",
        auth = AuthStyle.TOKEN,
    ),
    KODI(
        "Kodi",
        "Talks JSON-RPC rather than REST; browses the library Kodi already scanned.",
        urlHint = "http://192.168.0.10:8080",
    ),
    SMB(
        "SMB share (v2/v3)",
        "A folder on a NAS. Files rather than an API, so the app has to read the tags itself.",
        urlHint = "smb://nas.local/music",
    ),
    WEBDAV(
        "WebDAV",
        "A folder over HTTP. Same shape as SMB, and the app indexes and tags it.",
        urlHint = "https://dav.example.com/music",
    ),
    GOOGLE_DRIVE("Google Drive", "Sign in and index a folder of music.", auth = AuthStyle.LINKED_ACCOUNT),
    ONEDRIVE("OneDrive", "Sign in and index a folder of music.", auth = AuthStyle.LINKED_ACCOUNT),
    DROPBOX("Dropbox", "Sign in and index a folder of music.", auth = AuthStyle.LINKED_ACCOUNT),
    BOX("Box", "Sign in and index a folder of music.", auth = AuthStyle.LINKED_ACCOUNT),
    PCLOUD("pCloud", "Sign in and index a folder of music.", auth = AuthStyle.LINKED_ACCOUNT),
    LOCAL(
        "This device",
        "Music already on the phone, or on an SD card.",
        supported = true,
        auth = AuthStyle.NONE,
    ),
    DOWNLOADS(
        "Downloads",
        "Everything saved for offline. Plays with every server switched off.",
        supported = true,
        auth = AuthStyle.NONE,
    );

    /**
     * Music Assistant is the one kind the app does not play itself. See [MusicSource].
     */
    val playsLocally: Boolean get() = this != MUSIC_ASSISTANT

    /**
     * Which picker heading this kind sits under, or null when it is not in the
     * grouped list at all: the streaming accounts render under Experimental, the
     * planned kinds under Not yet supported, and [DOWNLOADS] is never offered as a
     * row. Family is presentational — it groups the stable kinds people recognise
     * servers by, it never decides what can be built or played.
     */
    val family: Family? get() = Family.entries.firstOrNull { this in it.kinds }

    /**
     * The families the stable picker groups its rows under, in display order.
     *
     * The point is recognition at setup time: someone running moOde or Ampache or
     * Funkwhale does not know which API their server speaks, so "MPD" or
     * "Subsonic-compatible" alone does not answer them. Naming the servers a
     * family covers — in the heading where one is needed, in the blurb where the
     * row is the family — is what makes them see theirs.
     *
     * Single-member families ([MUSIC_ASSISTANT], [MPD], [LOCAL_DEVICE]) exist so
     * every row renders through one loop; their labels are skipped by the picker,
     * since a heading reading "Music Assistant" directly above the Music Assistant
     * row is noise.
     */
    enum class Family(val label: String, val kinds: Set<ServerKind>) {
        // `ServerKind.` is not decoration: Family declares entries with the same
        // names (MUSIC_ASSISTANT, MPD), and unqualified they would resolve to those.
        MUSIC_ASSISTANT("Music Assistant", setOf(ServerKind.MUSIC_ASSISTANT)),
        SUBSONIC_COMPATIBLE("Subsonic servers", setOf(ServerKind.NAVIDROME, ServerKind.SUBSONIC)),
        MEDIA_SERVER("Media servers", setOf(ServerKind.JELLYFIN, ServerKind.EMBY, ServerKind.PLEX)),
        MPD("MPD", setOf(ServerKind.MPD)),
        LOCAL_DEVICE("This device", setOf(ServerKind.LOCAL)),
    }

    /**
     * Whether this kind points at a host the user types an address for.
     *
     * Deliberately not the same question as "has a setup form": a streaming account
     * (Spotify, Qobuz, Tidal) has a form and no host — it is an account, not a
     * server. The two questions were one property (`auth != NONE`), which held while
     * every credentialed kind happened to live on someone's LAN and broke the moment
     * a library's answer to "where is it" was a login instead of a URL.
     */
    val hasAddress: Boolean get() = !cloudAccount && auth != AuthStyle.NONE

    /** Whether a setup form has credentials to ask for. */
    val needsCredentials: Boolean get() = auth != AuthStyle.NONE

    /**
     * Whether a blank URL is a reason to refuse to connect.
     *
     * Replaces the hardcoded `== ServerKind.LOCAL` tests that used to gate "does this
     * library count as configured" and "is a blank url a reason to bail out of
     * connecting". Both were about there being nothing to type, not about that one
     * kind, and every kind that reads something already on the phone hits them. A
     * streaming account belongs with them: its login *is* the address.
     */
    val needsAddress: Boolean get() = hasAddress && needsCredentials

    companion object {
        /** The ones a user can actually add today, in the order the picker shows them. */
        val available: List<ServerKind> get() = entries.filter { it.supported }

        /**
         * The subset a user can *add*. [DOWNLOADS] is always present and cannot be
         * created, renamed away or removed — it describes files this app itself put on
         * the phone, so there is nothing to configure and nothing to point elsewhere.
         */
        val addable: List<ServerKind> get() = available.filterNot { it == DOWNLOADS }

        /** The rest, so the picker can show where this is going. */
        val planned: List<ServerKind> get() = entries.filterNot { it.supported }

        /**
         * The addable kinds that are settled — the picker's main list. The
         * experimental ones are equally addable and are offered right below,
         * under their own heading, so the split is presentational: one list, two
         * headings, nothing hidden.
         */
        val addableStable: List<ServerKind> get() = addable.filterNot { it.experimental }

        /** The addable kinds still flagged [experimental]. */
        val addableExperimental: List<ServerKind> get() = addable.filter { it.experimental }

        fun from(name: String?): ServerKind? = entries.firstOrNull { it.name == name }
    }
}

/** Which credential fields a kind's setup form needs. */
@Serializable
enum class AuthStyle {
    NONE,
    /** Address, username, password. */
    USER_PASSWORD,
    /** Address required; credentials only if the server asks for them. */
    OPTIONAL_USER_PASSWORD,
    /** Address plus a long-lived token pasted in. */
    TOKEN,
    /** An OAuth or PIN flow against the provider — no password is ever typed here. */
    LINKED_ACCOUNT,
}

/**
 * One configured server.
 *
 * Identified by a generated [id] rather than by its URL, so renaming a server or
 * moving it to a new address doesn't orphan the downloads and settings that point at
 * it. [options] carries whatever a particular kind needs and the others don't —
 * Navidrome's stream format, Jellyfin's user and library ids — rather than growing a
 * nullable column per provider on a shared model.
 *
 * [password] and [token] are stored encrypted; see `AppSettings.servers`.
 */
@Serializable
data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val kind: ServerKind,
    /** What the user calls it. Blank falls back to the kind's own label. */
    val label: String = "",
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val token: String = "",
    val options: Map<String, String> = emptyMap(),
) {
    val displayName: String get() = label.ifBlank { kind.label }

    /** Host and port, for the second line of a server card. */
    val host: String
        get() = url.trim()
            .removePrefix("https://").removePrefix("http://")
            .trimEnd('/')
            .ifBlank { "Not set up" }

    fun option(key: String): String? = options[key]?.takeIf { it.isNotBlank() }

    fun withOption(key: String, value: String): ServerConfig =
        copy(options = options + (key to value))

    companion object {
        /** Navidrome / Subsonic: what `format=` the stream URL asks for. */
        const val OPT_STREAM_FORMAT = "streamFormat"

        // ── Music Assistant player settings ───────────────────────────────
        //
        // These describe *this phone as a player registered with one MA server*, not
        // the phone in general, and they used to be app-global keys. With one MA
        // server that reads the same either way; with two, both would share a player
        // name, a codec and a target — and the second server would silently overwrite
        // the first's answers. They live here so the server, its library and its
        // player are configured in one place and stored in one place.

        /** What this phone is called on that server. */
        const val OPT_PLAYER_NAME = "playerName"

        /** Which codec the Sendspin `client/hello` advertises to it. */
        const val OPT_SENDSPIN_CODEC = "sendspinCodec"

        /** Whether to ask that server for hi-res, and for FLAC. */
        const val OPT_PREFER_HI_RES = "preferHiRes"
        const val OPT_PREFER_FLAC = "preferFlac"

        /** Hold the Sendspin socket open so announcements arrive without a delay. */
        const val OPT_KEEP_ALIVE = "keepAliveAnnouncements"

        /** The player on that server this phone is currently controlling. */
        const val OPT_TARGET_PLAYER = "targetPlayer"

        /** This phone's latency trim against that server's clock, in milliseconds. */
        const val OPT_STATIC_DELAY_MS = "staticDelayMs"

        /** Jellyfin/Emby: the authenticated user's id, needed on most of its endpoints. */
        const val OPT_USER_ID = "userId"

        /** Jellyfin/Emby: which of the server's libraries to browse. Plex: the music section's key. */
        const val OPT_LIBRARY_ID = "libraryId"

        /** Qobuz: the app id + secret registered to this app, for the signed API calls. */
        const val OPT_QOBUZ_APP_ID = "qobuzAppId"
        const val OPT_QOBUZ_APP_SECRET = "qobuzAppSecret"

        /** Tidal: the client credentials of this app's own developer registration. */
        const val OPT_TIDAL_CLIENT_ID = "tidalClientId"
        const val OPT_TIDAL_CLIENT_SECRET = "tidalClientSecret"

        /** Tidal: the device-flow session, persisted so sign-in survives restarts. */
        const val OPT_TIDAL_ACCESS_TOKEN = "tidalAccessToken"
        const val OPT_TIDAL_REFRESH_TOKEN = "tidalRefreshToken"
        const val OPT_TIDAL_TOKEN_EXPIRES_AT = "tidalTokenExpiresAt"
        const val OPT_TIDAL_USER_ID = "tidalUserId"
        const val OPT_TIDAL_COUNTRY_CODE = "tidalCountryCode"

    }
}
