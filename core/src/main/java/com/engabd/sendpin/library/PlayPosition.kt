package com.engabd.sendpin.library

/**
 * The position a "listened to" report should carry to a server that keeps resume
 * points (Jellyfin, Emby, Plex).
 *
 * [positionMs], the player's own tracked position, wins whenever it is there; callers
 * on the local-playback path always have it. The wall-clock fallback only runs when a
 * caller genuinely has nothing else: it drifts across a pause (elapsed time keeps
 * counting, playback doesn't), so it is strictly worse, and exists only so a report
 * never regresses to sending 0, which left the server unable to build a resume point.
 *
 * Here rather than in one provider because three of them report the same way; it was
 * `JellyfinSource.resolvePosition`, which still delegates here.
 */
fun resolvePlayPosition(
    positionMs: Long?,
    startedAtMs: Long?,
    nowMs: Long = System.currentTimeMillis(),
): Long = positionMs
    ?: startedAtMs?.let { (nowMs - it).coerceAtLeast(0L) }
    ?: 0L
