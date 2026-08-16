package com.engabd.sendpin.discovery

import android.os.Build
import android.provider.Settings
import com.engabd.sendpin.BuildConfig
import com.engabd.sendpin.protocol.DeviceInfo
import java.util.*

object PlayerIdentity {
    /**
     * Volatile because [newIdentity] clears it from whatever coroutine a rename
     * happens on, while [getPlayerId] is read from view models, the Sendspin client
     * and `MaNowPlaying`'s polling loop — all on other threads. Without it a reader
     * can go on seeing the previous id indefinitely, which is the whole failure this
     * cache exists one line away from causing.
     */
    @Volatile private var cachedId: String? = null
    private var cachedDeviceInfo: DeviceInfo? = null

    private const val PREFS = "player_identity"
    private const val KEY_GENERATION = "generation"

    /**
     * This phone's Music Assistant player id.
     *
     * **Read this live; never capture it in a `val`.** [newIdentity] mints a new one
     * mid-session (a rename is the common trigger — see `Playback.reregister` and
     * `applyPlayerConfig`), and a holder that captured the old one goes on addressing
     * a player Music Assistant now considers unavailable: the play command either
     * fails with MA's own "player is not available", or is queued to a stale player
     * that isn't this phone. Either way the app looks like it is playing and no sound
     * comes out. Cheap to call — after the first read it is a field.
     */
    fun getPlayerId(context: android.content.Context): String {
        cachedId?.let { return it }

        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()

        val raw = "${Build.MANUFACTURER}-${Build.MODEL}-$androidId-${generation(context)}"
        cachedId = UUID.nameUUIDFromBytes(raw.toByteArray()).toString()
        return cachedId!!
    }

    /**
     * Bumping this mints a new player id, which Music Assistant sees as a new player.
     *
     * The escape hatch for a name that won't change. MA identifies a Sendspin player by
     * its `client_id` and keeps the name it was **first registered under** — the
     * protocol has no rename message ("no mechanism for updating the client name
     * post-connection"), so a player registered under the phone's model keeps that name
     * until its server-side config is edited. Where that edit is refused, registering
     * afresh is the only thing left, and it always works.
     *
     * Read through SharedPreferences rather than DataStore because [getPlayerId] is
     * synchronous and called from constructors.
     */
    private fun generation(context: android.content.Context): Int =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getInt(KEY_GENERATION, 0)

    /** Mint a new identity. The old player is left behind in MA for the user to remove. */
    fun newIdentity(context: android.content.Context) {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_GENERATION, generation(context) + 1).apply()
        cachedId = null
    }

    /**
     * What this client *is*, not what this player is *called*.
     *
     * Deliberately constant rather than `Build.MODEL` / `Build.MANUFACTURER`. Music
     * Assistant composes a newly-discovered player's name from what the client
     * announces, so putting the hardware here is what made every rename read back as
     * the phone's model. Both reference clients do the same: the official app sends
     * `model = "Mobile Application", manufacturer = "Music Assistant"`, massdroid
     * sends `"Mobile Application" / "asksakis.net"`. The player's actual name travels
     * in `client/hello.payload.name`, and is changed afterwards through the Music
     * Assistant API — see `MaRepository.renamePlayer`.
     */
    fun getDeviceInfo(): DeviceInfo {
        cachedDeviceInfo?.let { return it }

        cachedDeviceInfo = DeviceInfo(
            productName = "Sendpin",
            manufacturer = "Sendpin",
            softwareVersion = BuildConfig.VERSION_NAME,
        )
        return cachedDeviceInfo!!
    }

    /** The local fallback label when the user hasn't named the player yet. */
    fun getDefaultPlayerName(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}"
            .replaceFirstChar { it.uppercase() }
    }
}
