package com.engabd.sendpin.hue

/**
 * Whatever turns [SyncoEngine]'s per-channel colour map into light on a wire.
 *
 * [DirectLightSync] renders once per frame — [SyncoEngine], the layer chain,
 * [FieldSafety], the rate limiter, Rhythm Lights and the ambience shows all
 * finish their work at one `Map<Int, Rgb>` — and hands that same map to
 * whichever [LightBridge] is active. A bridge owns everything downstream of
 * it: discovery, session lifecycle, wire format, reconnect policy. None of
 * the show-producing code above ever needs to know which bridge is running.
 *
 * [EntertainmentChannel] (a channel id plus a room position, defined for the
 * Hue CLIP API) is reused here as the generic per-channel description rather
 * than inventing a parallel type: [SyncoEngine], [SpatialWaves]'
 * `classifyTopology`/`normalizePositions`, and the render loop's game-lamp
 * classification all already consume `List<EntertainmentChannel>` and are
 * bridge-agnostic today in fact, not just in principle. A non-Hue bridge
 * (WLED — see `docs/plan/wled-light-backend.md`) constructs synthetic
 * channels of its own: one per LED segment, with a position the user
 * supplies instead of one read from a Hue entertainment area, `members`
 * left empty and `gamut` left null (WLED strips take raw sRGB, so there is
 * no gamut to clamp to).
 *
 * [HueLightBridge] is the first implementation, wrapping the existing
 * [HueBridgeClient] / [DtlsPskClient] / [HueStreamEncoder] with **no
 * behaviour change** — see `docs/plan/wled-light-backend.md`'s Phase 0. It
 * is the only implementation [DirectLightSync] constructs today; the
 * `bridge: LightBridge` field it renders through exists so a second one can
 * be selected later without touching the show.
 */
interface LightBridge {

    /** Bridge-specific room description: channel ids, positions, topology hint. */
    suspend fun fetchRoom(): LightRoom

    /**
     * Claim the device(s) for streaming, following [room]'s channel layout.
     * Throws on failure — [DirectLightSync.start] maps that to `_error`.
     */
    suspend fun openSession(room: LightRoom)

    /** Release the device(s). Safe to call when no session is open. */
    suspend fun closeSession()

    /** Encode one rendered frame into whatever this wire sends. May split into several packets. */
    fun encode(colors: Map<Int, Rgb>): List<ByteArray>

    /** Put one already-encoded packet on the wire. */
    suspend fun send(packet: ByteArray): SendOutcome

    /**
     * Watch for a bridge-initiated teardown between frames — the Hue app
     * taking the entertainment area, say. Returns a user-facing message
     * once the session has been revoked, null otherwise. Called from
     * [DirectLightSync]'s keepalive loop, not the render loop; a bridge
     * with nothing to poll (WLED has no session to revoke) simply always
     * returns null.
     */
    suspend fun pollRevocation(): String?

    /**
     * Rebuild the session after repeated send failures, following [room]'s
     * channel layout. Backs off internally between attempts; returns false
     * once exhausted or once [shouldContinue] turns false — checked by the
     * bridge both before waiting and after, so a caller that has stopped
     * running is never kept waiting on a doomed retry. [onAttempt] fires
     * once per attempt, before it runs, so the caller can mirror progress
     * to the UI without the bridge owning any UI-facing state itself.
     */
    suspend fun reconnect(room: LightRoom, shouldContinue: () -> Boolean, onAttempt: () -> Unit = {}): Boolean
}

/** What [LightBridge.send] tells [DirectLightSync]'s render loop to do next. */
sealed class SendOutcome {
    /** Delivered — or, for a connectionless wire, sent best-effort. */
    object Ok : SendOutcome()

    /**
     * The *bridge* ended the session — never retried. Retrying here is what
     * makes a bridge's own stop control look broken, because the very next
     * `action: start` would take the session straight back.
     */
    data class Revoked(val message: String) : SendOutcome()

    /** A transient fault. The caller counts these toward triggering [LightBridge.reconnect]. */
    data class Failed(val message: String) : SendOutcome()
}

/**
 * A bridge's room: the channels [SyncoEngine] renders into, and the Hue
 * `configuration_type` ("room" | "screen") its constructor also wants.
 * A bridge with no such distinction (WLED) leaves it at the default.
 */
data class LightRoom(
    val channels: List<EntertainmentChannel>,
    val configurationType: String = "room",
    /** Human-readable name, for logging — the Hue entertainment area's own name. */
    val name: String = "",
)
