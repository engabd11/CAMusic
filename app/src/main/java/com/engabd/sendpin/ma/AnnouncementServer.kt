package com.engabd.sendpin.ma

import com.engabd.sendpin.party.PartyAuth
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.util.Timer
import kotlinx.coroutines.CompletableDeferred
import kotlin.concurrent.schedule

/**
 * Hands one announcement clip to Music Assistant, which plays an announcement by
 * fetching a URL (through ffmpeg) rather than taking the audio over its API.
 *
 * Same shape as [com.engabd.sendpin.party.PartyServer], deliberately not part of it:
 * the party server exposes no files at all, and this one exposes exactly one. It binds
 * to [hostname] only (one of the phone's addresses MA may reach, never
 * 0.0.0.0), answers only `/<token>/<file name>` with a random 128-bit token, and stops
 * itself after [lifetimeMs]. Stopping deletes the clip, so nothing outlives it.
 *
 * Served as many times as asked while it lives, not once: MA probes the clip for its
 * duration and then streams it, and does both again for every speaker it was sent to.
 */
class AnnouncementServer(
    hostname: String,
    private val file: File,
    private val contentType: String,
    lifetimeMs: Long = LIFETIME_MS,
    private val token: String = PartyAuth.newToken(),
    private val clock: () -> Long = System::currentTimeMillis,
) : NanoHTTPD(hostname, 0) {

    private val expiresAt = clock() + lifetimeMs
    private val timer = Timer("announce-expiry", true).apply { schedule(lifetimeMs) { stop() } }
    private val path = "/$token/${file.name}"

    /** Completes on the first request for the clip: proof MA reached this address. */
    val fetched = CompletableDeferred<Unit>()

    fun begin() = start(READ_TIMEOUT_MS, true)

    /** Where Music Assistant fetches the clip. Valid once [begin] has returned. */
    val url: String get() = "http://$hostname:$listeningPort$path"

    override fun serve(session: IHTTPSession): Response {
        val get = session.method == Method.GET || session.method == Method.HEAD
        // Past its life it answers like any wrong path, even before the timer fires.
        if (!get || session.uri != path || clock() >= expiresAt || !file.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
        fetched.complete(Unit)
        return newFixedLengthResponse(Response.Status.OK, contentType, FileInputStream(file), file.length())
            .apply { addHeader("Cache-Control", "no-store") }
    }

    /** Stop serving but keep the clip, so it can be offered again on another address. */
    fun stopKeepingClip() {
        timer.cancel()
        super.stop()
    }

    override fun stop() {
        stopKeepingClip()
        file.delete()
    }

    companion object {
        const val LIFETIME_MS = 60_000L
        const val READ_TIMEOUT_MS = 5_000
    }
}
