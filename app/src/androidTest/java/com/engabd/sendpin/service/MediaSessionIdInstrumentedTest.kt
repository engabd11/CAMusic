package com.engabd.sendpin.service

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two media sessions in one process must not collide.
 *
 * This is 0.1, and it is the reason this file exists at all. `SendspinService` and
 * `LocalPlaybackService` each build a media3 [MediaSession], and media3 throws
 * `IllegalStateException: Session ID must be unique` when a second session is created
 * with an id another live session already holds. Both used to take the default id,
 * which is the empty string — so the crash was not a rare race but a certainty the
 * moment both services were up, and both services being up is the ordinary state of
 * an app that plays from two backends.
 *
 * The fix was one `setId` call each. This is what stops the next person removing them
 * as noise, and it is deliberately written against the *real* API rather than against
 * the two string constants: asserting `"local" != "sendspin"` would pass forever
 * without proving that media3 still enforces what the fix relies on.
 *
 * Instrumented because a [MediaSession] needs a real Context, a real Looper and a real
 * [ExoPlayer]. Everything is released in a `finally`, since a leaked session would
 * make the *next* test in the run fail for a reason that has nothing to do with it.
 */
@RunWith(AndroidJUnit4::class)
class MediaSessionIdInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** The ids the two services actually use — see each service's `setupMediaSession`. */
    private val localId = "local"
    private val sendspinId = "sendspin"

    @Test
    fun bothServicesSessionsCanExistAtOnce() {
        assertNotEquals("the two services must not claim the same session id", localId, sendspinId)

        var playerA: ExoPlayer? = null
        var playerB: ExoPlayer? = null
        var sessionA: MediaSession? = null
        var sessionB: MediaSession? = null
        try {
            runOnMain {
                playerA = ExoPlayer.Builder(context).build()
                playerB = ExoPlayer.Builder(context).build()
                sessionA = MediaSession.Builder(context, playerA!!).setId(localId).build()
                // The line that used to throw. No assertion is needed beyond it
                // returning: `Session ID must be unique` is an exception, and the
                // whole of 0.1 was that exception reaching the crash buffer on any
                // launch where both backends had been used.
                sessionB = MediaSession.Builder(context, playerB!!).setId(sendspinId).build()
            }
        } finally {
            runOnMain {
                sessionB?.release(); sessionA?.release()
                playerB?.release(); playerA?.release()
            }
        }
    }

    @Test(expected = IllegalStateException::class)
    fun twoSessionsSharingAnIdStillThrow() {
        // The guard the fix depends on, asserted rather than assumed. If a future
        // media3 stopped enforcing uniqueness, the test above would keep passing
        // while saying nothing — and the `setId` calls it protects would look
        // removable. This fails loudly instead.
        var playerA: ExoPlayer? = null
        var playerB: ExoPlayer? = null
        var sessionA: MediaSession? = null
        var sessionB: MediaSession? = null
        try {
            runOnMain {
                playerA = ExoPlayer.Builder(context).build()
                playerB = ExoPlayer.Builder(context).build()
                sessionA = MediaSession.Builder(context, playerA!!).setId("collide").build()
                sessionB = MediaSession.Builder(context, playerB!!).setId("collide").build()
            }
        } finally {
            runOnMain {
                sessionB?.release(); sessionA?.release()
                playerB?.release(); playerA?.release()
            }
        }
    }

    /**
     * ExoPlayer and MediaSession are main-thread-only, and an instrumentation test
     * body is not on it. Rethrown rather than swallowed so the expected-exception
     * test above still sees its exception.
     */
    private fun runOnMain(block: () -> Unit) {
        var thrown: Throwable? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { block() } catch (t: Throwable) { thrown = t } finally { latch.countDown() }
        }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        thrown?.let { throw it }
    }
}
