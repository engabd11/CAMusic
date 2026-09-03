package com.engabd.sendpin.library

import android.content.Context
import com.engabd.sendpin.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Every configured library that can hand this phone an audio **file**, whether or
 * not it is the one currently playing.
 *
 * ## Why this has to exist
 *
 * Offline analysis needs bytes. Everywhere else in the app "the library" means the
 * one being browsed, and that is the right answer because that is also the one
 * handing out stream URLs. MPD breaks the assumption in both directions at once: it
 * is the library *and* the player, and it is the one provider with no endpoint that
 * gives a file up at all — see [MpdSource][com.engabd.sendpin.library.MpdSource]'s
 * note on [Capability.DOWNLOAD]. So on an MPD session the active source is
 * precisely the source that cannot answer, and asking it was the whole of why
 * Light Sync sat at "analysing" for ever and never lit anything: the resolver
 * built a track whose stream URL was the empty string MPD returns, the scanner
 * took one look at it and returned without a word, and nothing ever completed.
 *
 * The music, meanwhile, is very often *also* on something that will serve it. This
 * audience runs MPD on the box with the DAC and Navidrome for everything else over
 * the same files; the phone may have some of it downloaded; a device-local folder
 * source reads the same album off an SD card. Any of those can supply the bytes for
 * a track MPD is playing, and the scan that comes out is keyed to the copy that was
 * analysed, so it is found again next time without a second fetch.
 *
 * ## What it does not do
 *
 * It does not connect anything eagerly, and it never touches the active session.
 * The active source is passed in rather than rebuilt, so an MPD session's own
 * connection is not duplicated and a Jellyfin session is not opened twice. A
 * companion is built at most once per process and cached; one that cannot be built
 * or signed into is written off for the session rather than retried on every track,
 * because the alternative is an authentication attempt against a server that is
 * down, once per song, for as long as the app is open.
 */
class CompanionLibraries(
    private val context: Context,
    private val settings: AppSettings,
    /**
     * The library being browsed and played right now.
     *
     * Offered first when it can serve files — on every backend but MPD it is both
     * the nearest copy and the one already connected — and never rebuilt here.
     */
    private val active: () -> MusicSource?,
) {

    private val lock = Mutex()
    private val built = LinkedHashMap<String, MusicSource>()
    private val writtenOff = mutableSetOf<String>()

    /**
     * The sources worth asking for a file, best first.
     *
     * Suspending because building one can mean a sign-in. Serialised on [lock] so
     * two tracks starting at once do not authenticate the same server twice.
     */
    suspend fun all(): List<MusicSource> = lock.withLock {
        val out = LinkedHashMap<String, MusicSource>()
        active()?.takeIf { canServeFiles(it) }?.let { out[keyOf(it)] = it }

        val configs = runCatching { settings.servers.first() }.getOrDefault(emptyList())
        for (config in configs) {
            if (!config.kind.supported || config.kind in CANNOT_SERVE) continue
            if (config.id in writtenOff) continue
            val ready = built[config.id] ?: build(config) ?: continue
            out.putIfAbsent(keyOf(ready), ready)
        }
        out.values.toList()
    }

    /**
     * Stand a companion up, or write it off for the session.
     *
     * `prepare` as well as `create`, because a Jellyfin, Emby or Plex source is not
     * usable until it has a session and a library id — and swallowed, because a
     * server being down is a reason for this track's lights to stay off, never a
     * reason for anything the user can see to fail.
     */
    private suspend fun build(config: ServerConfig): MusicSource? {
        // On IO explicitly: `prepare` signs in, and the callers run on
        // `Dispatchers.Default`, whose threads are sized for CPU work rather than
        // for sitting on a socket.
        val made = withContext(Dispatchers.IO) {
            runCatching {
                MusicSources.create(context, config)?.also { MusicSources.prepare(it, config) }
            }.getOrNull()
        }
        if (made == null || !canServeFiles(made)) {
            writtenOff += config.id
            return null
        }
        made.streamFormat = "raw"
        built[config.id] = made
        return made
    }

    /**
     * A source worth asking for a file.
     *
     * [Capability.DOWNLOAD] is the general test, and the device-local folder source
     * is the one honest exception: it advertises no download because the file is
     * already on this phone, which is exactly what makes it the best companion
     * there is. It answers `downloadUrl` with a MediaStore content URI the scanner
     * opens directly, with no network at all.
     */
    private fun canServeFiles(source: MusicSource): Boolean =
        source.has(Capability.DOWNLOAD) || source.kind == ServerKind.LOCAL

    /** Two configs pointing at the same server are one source to ask. */
    private fun keyOf(source: MusicSource): String = source.providerId + "|" + source.serverUrl

    private companion object {
        /**
         * Kinds with no file to give.
         *
         * MPD serves audio only to its own outputs. Downloads is not skipped for
         * want of files but because the callers search the download index directly
         * and by identity, which is both cheaper and a better match than a text
         * search over the same rows.
         */
        val CANNOT_SERVE = setOf(
            ServerKind.MPD,
            ServerKind.DOWNLOADS,
            ServerKind.MUSIC_ASSISTANT,
        )
    }
}
