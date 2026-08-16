package com.engabd.sendpin.discovery

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A rename must reach every reader, immediately.
 *
 * This is 0.2c, which cost a release. The v0.9 plan recorded Music Assistant's
 * "player is not available" as "not a code bug"; it was one. `newIdentity` mints a
 * fresh `client_id` — the only way to shake off a name MA has already committed to —
 * and **eight** places had captured the old one in a `val`. Each went on addressing a
 * player MA now considered unavailable, so playback either failed with MA's own error
 * or was queued to a stale player that was not this phone. From the outside the app
 * looked like it was playing and no sound came out. Re-selecting the player in
 * Speakers *masked* it, which is why it survived so long.
 *
 * Instrumented rather than a JVM test because [PlayerIdentity] reads
 * `Settings.Secure.ANDROID_ID` and SharedPreferences — there is no Context to fake
 * that is worth the faking.
 */
@RunWith(AndroidJUnit4::class)
class PlayerIdentityInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /**
     * The generation counter is real, persisted state on the device this runs on, so
     * it is put back afterwards. A test that quietly bumped a developer's player id
     * every run would be renaming their actual speaker.
     */
    private var savedGeneration = 0

    @Before
    fun remember() {
        savedGeneration = prefs().getInt("generation", 0)
    }

    @After
    fun restore() {
        prefs().edit().putInt("generation", savedGeneration).apply()
        PlayerIdentity.newIdentity(context)
        prefs().edit().putInt("generation", savedGeneration).apply()
        // Force the cache to reload from the restored generation.
        PlayerIdentity.getPlayerId(context)
    }

    private fun prefs() =
        context.getSharedPreferences("player_identity", android.content.Context.MODE_PRIVATE)

    @Test
    fun playerIdIsStableUntilItIsDeliberatelyChanged() {
        val first = PlayerIdentity.getPlayerId(context)
        assertEquals(
            "the same install must present the same player to Music Assistant on every read",
            first,
            PlayerIdentity.getPlayerId(context),
        )
    }

    @Test
    fun newIdentityIsVisibleOnTheVeryNextRead() {
        val before = PlayerIdentity.getPlayerId(context)
        PlayerIdentity.newIdentity(context)
        val after = PlayerIdentity.getPlayerId(context)

        assertNotEquals("newIdentity must actually mint a new player id", before, after)
        // The regression, stated directly: the *next* read returns the new id. Every
        // one of the eight bugs was a reader that had answered this question once and
        // kept the answer.
        assertEquals("the new id must be stable once minted", after, PlayerIdentity.getPlayerId(context))
    }

    @Test
    fun renamingTwiceGivesThreeDistinctIdentities() {
        // Music Assistant keeps the name a player was *first* registered under, so a
        // second rename needs a second new identity. A generation counter that only
        // ever advanced once would fix the first rename and silently break the second.
        val a = PlayerIdentity.getPlayerId(context)
        PlayerIdentity.newIdentity(context)
        val b = PlayerIdentity.getPlayerId(context)
        PlayerIdentity.newIdentity(context)
        val c = PlayerIdentity.getPlayerId(context)

        assertEquals("three renames should give three distinct players", 3, setOf(a, b, c).size)
    }
}
