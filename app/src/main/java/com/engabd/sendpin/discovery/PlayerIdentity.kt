package com.engabd.sendpin.discovery

import android.content.Context
import android.os.Build
import com.engabd.sendpin.BuildConfig
import com.engabd.sendpin.protocol.DeviceInfo
import com.engabd.sendpin.protocol.noise.PairingStore

object PlayerIdentity {
    private const val PREFS = "sendspin_pairing"
    private const val KEY_STORE = "store"

    @Volatile private var store: PairingStore? = null
    private var cachedDeviceInfo: DeviceInfo? = null

    /**
     * The phone's Sendspin identity, pairing PSK, pairing records and pairing toggles,
     * persisted as one JSON document in app-private preferences. Created on first use;
     * every reader of the player id goes through it.
     *
     * Reads and writes are synchronous (`commit`, not `apply`): a pairing record the
     * server has just finalised must be on disk before the socket that carries the next
     * handshake is answered.
     */
    fun pairingStore(context: Context): PairingStore {
        store?.let { return it }
        synchronized(this) {
            store?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val created = PairingStore(object : PairingStore.Persistence {
                override fun load(): String? = prefs.getString(KEY_STORE, null)
                override fun save(json: String) { prefs.edit().putString(KEY_STORE, json).commit() }
            })
            store = created
            return created
        }
    }

    /**
     * This phone's Music Assistant player id — the Sendspin `client_id`, which the
     * spec defines as the base64url X25519 public key of the phone's identity.
     *
     * **Read this live; never capture it in a `val`.** [newIdentity] mints a new one
     * mid-session (a rename is the common trigger — see `Playback.reregister` and
     * `applyPlayerConfig`), and a holder that captured the old one goes on addressing
     * a player Music Assistant now considers unavailable. Cheap to call — after the
     * first read it is a field.
     */
    fun getPlayerId(context: Context): String = pairingStore(context).identity.peerId

    /**
     * Mint a new identity, which Music Assistant sees as a new player.
     *
     * The escape hatch for a name that won't change: MA keeps the name a Sendspin player
     * was **first registered under** until its server-side config is edited, and where
     * that edit is refused, registering afresh is the only thing left. Every pairing
     * record goes with the old key, since the server verified it against that key. The
     * old player is left behind in MA for the user to remove.
     */
    fun newIdentity(context: Context) {
        pairingStore(context).regenerateIdentity()
    }

    /**
     * What this client *is*, not what this player is *called*.
     *
     * Deliberately constant rather than `Build.MODEL` / `Build.MANUFACTURER`. Music
     * Assistant composes a newly-discovered player's name from what the client
     * announces, so putting the hardware here is what made every rename read back as
     * the phone's model. Deliberately *not* one of the names MA classes as a web/app
     * player ("Mobile Application", "Web Browser", …) either: those players are hidden,
     * private and kept out of Home Assistant by default, and this one is a room player.
     * The player's actual name travels in `client/hello.payload.name`, and is changed
     * afterwards through the Music Assistant API — see `MaRepository.renamePlayer`.
     */
    fun getDeviceInfo(): DeviceInfo {
        cachedDeviceInfo?.let { return it }

        cachedDeviceInfo = DeviceInfo(
            productName = "CAMusic",
            manufacturer = "CAMusic",
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
