package com.engabd.sendpin.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves `scheme://id` stream uris to real, playable urls at the moment a player
 * opens them.
 *
 * ## Why this exists
 *
 * Every self-hosted server hands out stream urls that are stable for as long as the
 * server runs — `SubsonicSource.streamUrl` builds the string, the queue holds it for
 * hours, and it plays the same at the end as at the start. Signed streaming services
 * break that assumption: Qobuz's `track/getFileUrl` answer is signed per request and
 * expires within minutes, so a url baked into a queue at build time would be dead
 * before the second track started.
 *
 * The fix is to never bake the real url at all. A source whose urls expire puts a
 * cheap, stable **scheme uri** in the queue (`qobuz://track/123`) — every queue
 * builder, download fallback and car bridge treats it exactly like any other url,
 * because it is just a string — and the player resolves the scheme to the real,
 * freshly-signed https url at open time via media3's `ResolvingDataSource`.
 *
 * ## The contract
 *
 * A source registers a handler under its scheme name when it is created and
 * unregisters it when it is torn down; a re-register replaces, so the handler always
 * belongs to the most recently built source (the one whose session is live). `resolve`
 * is called on the player's loading thread, one `Network`-dispatcher hop off it — the
 * handler itself does a blocking HTTP round-trip and is expected to be quick and rare
 * (once per track).
 *
 * A scheme with no handler resolves to an [IOException] rather than a garbage url:
 * that is a load error the player already knows how to surface and skip, which is the
 * honest answer for a queue built before its source was torn down.
 */
object StreamSchemes {

    /**
     * Resolves [id] to a playable url, or throws. Implementations do network I/O;
     * the caller has already left the main thread.
     */
    fun interface Handler {
        suspend fun resolve(id: String): String
    }

    private val handlers = java.util.concurrent.ConcurrentHashMap<String, Handler>()

    /** Register (or replace) the handler for [scheme]. */
    fun register(scheme: String, handler: Handler) {
        handlers[scheme] = handler
    }

    /** Remove the handler for [scheme], if any. */
    fun unregister(scheme: String) {
        handlers.remove(scheme)
    }

    /** Whether a handler is registered for [scheme] — the resolver's cheap pre-check. */
    fun knows(scheme: String): Boolean = handlers.containsKey(scheme)

    /**
     * Resolve `scheme://id`-style [scheme] + [id] to a playable url.
     *
     * @throws java.io.IOException when no handler is registered for [scheme] — the
     *   player surfaces this as a load error and moves on, which is the right answer
     *   for a stale queue entry.
     */
    suspend fun resolve(scheme: String, id: String): String {
        val handler = handlers[scheme]
            ?: throw java.io.IOException("No resolver for stream scheme \"$scheme\"")
        // One hop off the player's loading thread for the handler's own dispatcher
        // hops (an HTTP call inside the handler wants Dispatchers.IO).
        return withContext(Dispatchers.IO) {
            handler.resolve(id)
        }
    }
}
