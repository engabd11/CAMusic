package com.engabd.sendpin.car

import android.content.ComponentName
import android.content.Context
import android.media.browse.MediaBrowser
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one thing about Android Auto that can be proven without a car.
 *
 * CAMusic did not appear in the Android Auto launcher in two different vehicles, and
 * every static explanation was ruled out by dumping the shipped APK: the
 * `com.google.android.gms.car.application` meta-data, the `<automotiveApp><uses
 * name="media"/>` descriptor and the exported service with the
 * `android.media.browse.MediaBrowserService` action all survive R8 and resource
 * shrinking intact, and Auto's "unknown sources" developer option was already on.
 * That leaves the handshake, which nothing in the source tree can answer.
 *
 * **Android Auto is a legacy media browser.** It does not speak media3's own session
 * protocol; it binds the `android.media.browse.MediaBrowserService` action and drives
 * it through `MediaBrowserCompat`, which on every API this app supports is a thin
 * wrapper over the platform's [MediaBrowser]. So the platform class is used here
 * deliberately, in preference to `MediaBrowserCompat` — it exercises the identical
 * binder path, it is what the compat class delegates to anyway, and it needs no
 * `androidx.media` dependency that the app does not otherwise have.
 *
 * What this pins, in the order the car asks for it:
 *  - the service binds and the browser reaches `onConnected` inside a timeout, which
 *    is the step a car silently gives up on;
 *  - the root id is the one the tree hands out, so nothing downstream is addressing a
 *    node that does not exist;
 *  - the root declares `CONTENT_STYLE_SUPPORTED`, without which several head units
 *    ignore every layout hint the app sends and use their own — indistinguishable,
 *    from the phone, from the app choosing that layout;
 *  - subscribing to the root delivers rows.
 *
 * A device with no libraries configured is a valid and expected state here: the tree
 * answers it with an explanatory row rather than an empty list, and that row is the
 * assertion. What must never happen is silence.
 */
@RunWith(AndroidJUnit4::class)
class CarMediaBrowserConnectTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val service = ComponentName(context, CarMediaLibraryService::class.java)

    @Test
    fun androidAutoStyleBrowserConnectsAndListsTheRoot() {
        val connected = CountDownLatch(1)
        var failed = false
        var browser: MediaBrowser? = null

        // The hints a head unit actually sends. The children limit is what decides how
        // many libraries reach the car's first screen before the rest go behind "More
        // libraries", so sending it exercises the split rather than the default path.
        val rootHints = Bundle().apply {
            putInt("androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_LIMIT", 4)
            putInt("android.media.extras.MEDIA_ART_SIZE_HINT_PIXELS", 320)
        }

        onMain {
            browser = MediaBrowser(
                context,
                service,
                object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() = connected.countDown()
                    override fun onConnectionFailed() {
                        failed = true
                        connected.countDown()
                    }

                    override fun onConnectionSuspended() {
                        failed = true
                        connected.countDown()
                    }
                },
                rootHints,
            )
            browser!!.connect()
        }

        try {
            assertTrue(
                "the browse service never answered the connection — this is the failure a car " +
                    "shows as the app simply not being in its launcher",
                connected.await(CONNECT_TIMEOUT_S, TimeUnit.SECONDS),
            )
            assertTrue("the browse service refused or dropped the connection", !failed)

            val b = browser!!
            assertEquals("the root id the tree hands out", CarMediaId.ROOT, b.root)

            assertTrue(
                "the root must declare CONTENT_STYLE_SUPPORTED, or head units ignore every " +
                    "layout hint this app sends",
                b.extras?.getBoolean(CarContentStyle.KEY_SUPPORTED) == true,
            )

            val children = subscribeToRoot(b)
            assertTrue(
                "the root returned no rows at all; with no libraries set up it should still " +
                    "return the row that says so",
                children.isNotEmpty(),
            )
        } finally {
            onMain { browser?.disconnect() }
        }
    }

    /** Subscribe to the root and wait for the first delivery. */
    private fun subscribeToRoot(browser: MediaBrowser): List<MediaBrowser.MediaItem> {
        val loaded = CountDownLatch(1)
        var items: List<MediaBrowser.MediaItem> = emptyList()
        onMain {
            browser.subscribe(
                browser.root,
                object : MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, children: List<MediaBrowser.MediaItem>) {
                        items = children
                        loaded.countDown()
                    }

                    override fun onError(parentId: String) = loaded.countDown()
                },
            )
        }
        assertTrue(
            "the root subscription was never answered",
            loaded.await(BROWSE_TIMEOUT_S, TimeUnit.SECONDS),
        )
        return items
    }

    /**
     * [MediaBrowser] is main-thread-only and an instrumentation test body is not on it.
     *
     * Note this only waits for the *call* to be made, never for the callback — the
     * latches above are what wait for the answer, which is the whole point of the test.
     */
    private fun onMain(block: () -> Unit) {
        var thrown: Throwable? = null
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                block()
            } catch (t: Throwable) {
                thrown = t
            } finally {
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.SECONDS)
        thrown?.let { throw it }
    }

    private companion object {
        /**
         * Generous on purpose. A car gives up far sooner than this, so a run that only
         * passes near the limit is still a bug — but a *failure* here should mean the
         * service never answered, not that a cold emulator was slow to start it.
         */
        const val CONNECT_TIMEOUT_S = 15L
        const val BROWSE_TIMEOUT_S = 20L
    }
}
