package com.engabd.sendpin.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] that lets cancellation through.
 *
 * `runCatching` catches every `Throwable`, and a coroutine is cancelled by a
 * [CancellationException] thrown out of its next suspension point — so wrapped
 * around a suspending call, `runCatching` turns "stop" into an ordinary failure:
 * the coroutine carries on to its fallback, the next request, the next loop turn,
 * after whoever owned it had already moved on. That is the difference between a
 * screen closing and its work stopping, and between a new track and the old
 * track's fetch still landing.
 *
 * Use this for the "try it, and fall back if it fails" pattern inside suspending
 * code. Keep plain `runCatching` in a `finally` or `NonCancellable` block, where a
 * cancelled coroutine's cleanup calls throw at once and swallowing that is what
 * lets the rest of the cleanup run.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
