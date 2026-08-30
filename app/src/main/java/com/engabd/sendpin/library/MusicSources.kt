package com.engabd.sendpin.library

import com.engabd.sendpin.emby.EmbyClient
import com.engabd.sendpin.emby.EmbyException
import com.engabd.sendpin.jellyfin.JellyfinClient
import com.engabd.sendpin.jellyfin.JellyfinException
import com.engabd.sendpin.plex.PlexClient
import com.engabd.sendpin.subsonic.SubsonicClient

/**
 * Turns a stored [ServerConfig] into a live [MusicSource].
 *
 * The one place that knows which client class serves which kind — so adding a
 * provider is this file plus its two new ones, and nothing else. Keeping the `when`
 * here rather than on the enum is deliberate: `ServerKind` is a serialised value that
 * has to stay free of client dependencies, and the construction of a Jellyfin session
 * is not a property of an enum constant.
 */
object MusicSources {

    /**
     * The provider tag the offline download index puts on its items.
     *
     * Lives here rather than in the library view model because it belongs to the same
     * question as [isLocalProvider]: a downloaded track is played by this phone, which
     * is what every caller is really asking.
     */
    const val DOWNLOAD_PROVIDER = "__dl__"

    /** Every provider tag a [MusicSource] can stamp on an item. */
    private val SOURCE_PROVIDERS: Set<String> = setOf(
        com.engabd.sendpin.subsonic.SubsonicClient.PROVIDER,
        com.engabd.sendpin.jellyfin.JellyfinClient.PROVIDER,
        EmbyClient.PROVIDER,
        PlexClient.PROVIDER,
        "local",
    )

    /**
     * Does an item with this provider tag belong to a library **this phone plays**?
     *
     * The alternative is Music Assistant, whose items carry its own provider domains.
     * Asked in a dozen places that used to compare against `SubsonicClient.PROVIDER`
     * directly — a test that silently answered "no, it's a Music Assistant item" for
     * every Jellyfin track the moment a second provider existed.
     */
    fun isLocalProvider(provider: String): Boolean =
        provider == DOWNLOAD_PROVIDER || provider in SOURCE_PROVIDERS


    /**
     * Build a source for [config], or null for a kind that has no adapter yet.
     *
     * Connecting is *not* done here. A Subsonic source authenticates per request and
     * needs nothing up front; a Jellyfin one has to sign in, which is a suspending
     * call that can fail and whose result has to be written back to the config. See
     * [prepare].
     */
    fun create(context: android.content.Context, config: ServerConfig): MusicSource? = when (config.kind) {
        ServerKind.NAVIDROME, ServerKind.SUBSONIC -> SubsonicSource(
            client = SubsonicClient(config.url, config.username, config.password).apply {
                streamFormat = config.option(ServerConfig.OPT_STREAM_FORMAT) ?: "raw"
            },
            kind = config.kind,
        )

        ServerKind.JELLYFIN -> JellyfinSource(
            JellyfinClient(
                baseUrl = config.url,
                token = config.token,
                userId = config.option(ServerConfig.OPT_USER_ID).orEmpty(),
                libraryId = config.option(ServerConfig.OPT_LIBRARY_ID).orEmpty(),
                // Per install, not per app. Jellyfin keys a session on the DeviceId, so
                // a constant shared by every copy of the app meant two phones on one
                // account fought over a single session in the server's dashboard.
                deviceId = com.engabd.sendpin.discovery.PlayerIdentity.getPlayerId(context),
            ).apply {
                streamFormat = config.option(ServerConfig.OPT_STREAM_FORMAT) ?: "raw"
            },
        )

        ServerKind.EMBY -> EmbySource(
            EmbyClient(
                baseUrl = config.url,
                token = config.token,
                userId = config.option(ServerConfig.OPT_USER_ID).orEmpty(),
                libraryId = config.option(ServerConfig.OPT_LIBRARY_ID).orEmpty(),
                deviceId = com.engabd.sendpin.discovery.PlayerIdentity.getPlayerId(context),
            ).apply {
                streamFormat = config.option(ServerConfig.OPT_STREAM_FORMAT) ?: "raw"
            },
        )

        // The token here is a plex.tv account token, minted by `PlexAuth`'s PIN flow
        // and already sitting in `config.token` by the time a source is built — see
        // [prepare], which for Plex has nothing left to exchange.
        ServerKind.PLEX -> PlexSource(
            PlexClient(
                baseUrl = config.url,
                token = config.token,
                librarySectionKey = config.option(ServerConfig.OPT_LIBRARY_ID).orEmpty(),
                clientIdentifier = com.engabd.sendpin.discovery.PlayerIdentity.getPlayerId(context),
            ).apply {
                streamFormat = config.option(ServerConfig.OPT_STREAM_FORMAT) ?: "raw"
            },
        )

        // Decoded with the same helper that encodes it. This used to `split("|")` a
        // string the settings screen wrote as a JSON array, and `Uri.parse` never
        // throws — so the whole array became one nonsensical uri, the folder list was
        // never empty, and nothing on the phone was ever inside it.
        ServerKind.LOCAL -> com.engabd.sendpin.local.LocalMediaSource(
            context = context,
            folderUris = com.engabd.sendpin.local.LocalFolders
                .decode(config.option(com.engabd.sendpin.local.LocalMediaSource.OPT_FOLDER_URIS))
                .mapNotNull { runCatching { android.net.Uri.parse(it) }.getOrNull() },
        )

        // Offline downloads, browsable like any other library. The index lives on the
        // process-scoped DownloadManager, so this is a view onto it rather than
        // anything with state of its own.
        ServerKind.DOWNLOADS -> com.engabd.sendpin.local.DownloadsSource(
            (context.applicationContext as com.engabd.sendpin.SendpinApp).downloads,
        )

        // Music Assistant is not a MusicSource — it owns a server-side queue and
        // plays to speakers this app never decodes for. See MusicSource's docs.
        ServerKind.MUSIC_ASSISTANT -> null

        // Listed in the picker, greyed, with docs/providers.md carrying the recipe.
        else -> null
    }

    /**
     * Get [source] into a state where it can answer questions, returning the config
     * updated with anything worth persisting.
     *
     * Two different things happen behind one call because the caller cares about
     * neither: a Subsonic server is asked which OpenSubsonic extensions it has, so
     * the capability set is real rather than assumed; a Jellyfin server is signed
     * into, which yields a token and a user id that must be stored or every launch
     * creates a new device session in its dashboard.
     *
     * Throws whatever the underlying client throws — a rejected password is worth
     * showing, and swallowing it here would leave a source that silently answers
     * nothing.
     */
    suspend fun prepare(source: MusicSource, config: ServerConfig): ServerConfig = when (source) {
        is SubsonicSource -> {
            source.probeCapabilities()
            config
        }

        is JellyfinSource -> {
            val client = source.jellyfin
            var updated = config
            // A stored token is used while it works, so an ordinary launch doesn't
            // leave a new device session in the server's dashboard every time. But it
            // is *checked* first, and a refused one is thrown away and replaced — the
            // alternative, trusting any non-blank token, meant a password change on
            // the server locked the user out with no way back except deleting the
            // server and adding it again.
            val tokenWorks = client.token.isNotBlank() && client.userId.isNotBlank() &&
                client.pingResult() == null
            if (!tokenWorks) {
                client.token = ""
                val (token, userId) = try {
                    client.authenticate(config.username, config.password)
                } catch (e: JellyfinException) {
                    if (e.isAuth) throw SourceAuthException(e.message ?: "Jellyfin refused that login")
                    throw e
                }
                updated = updated.copy(token = token).withOption(ServerConfig.OPT_USER_ID, userId)
            }
            // Without a library id every browse would return films and television
            // alongside the music. Picking the first music library is right for the
            // overwhelmingly common single-library server; the setup screen can offer
            // the choice where there is more than one.
            if (client.libraryId.isBlank()) {
                client.musicLibraries().firstOrNull()?.let {
                    client.libraryId = it.itemId
                    updated = updated.withOption(ServerConfig.OPT_LIBRARY_ID, it.itemId)
                }
            }
            updated
        }

        is EmbySource -> {
            val client = source.emby
            var updated = config
            val tokenWorks = client.token.isNotBlank() && client.userId.isNotBlank() &&
                client.pingResult() == null
            if (!tokenWorks) {
                client.token = ""
                val (token, userId) = try {
                    client.authenticate(config.username, config.password)
                } catch (e: EmbyException) {
                    if (e.isAuth) throw SourceAuthException(e.message ?: "Emby refused that login")
                    throw e
                }
                updated = updated.copy(token = token).withOption(ServerConfig.OPT_USER_ID, userId)
            }
            if (client.libraryId.isBlank()) {
                client.musicLibraries().firstOrNull()?.let {
                    client.libraryId = it.itemId
                    updated = updated.withOption(ServerConfig.OPT_LIBRARY_ID, it.itemId)
                }
            }
            updated
        }

        is PlexSource -> {
            val client = source.plex
            // The token was minted by `PlexAuth`'s PIN flow before this server was
            // ever saved, so there is no sign-in step here — only picking a music
            // section, the same reason Jellyfin picks a library above. A refused
            // token surfaces as the same `SourceAuthException` a bad password would,
            // so the connect form re-prompts rather than falling back to downloads.
            var updated = config
            val error = client.pingResult()
            if (error != null) {
                if (error.isAuth) throw SourceAuthException(error.message ?: "Plex refused that sign-in")
                throw error
            }
            if (client.librarySectionKey.isBlank()) {
                client.musicSections().firstOrNull()?.let {
                    client.librarySectionKey = it.itemId
                    updated = updated.withOption(ServerConfig.OPT_LIBRARY_ID, it.itemId)
                }
            }
            updated
        }

        else -> config
    }
}
