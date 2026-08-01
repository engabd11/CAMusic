package com.engabd.sendpin.discovery

import android.os.Build
import android.provider.Settings
import com.engabd.sendpin.BuildConfig
import com.engabd.sendpin.protocol.DeviceInfo
import java.util.*

object PlayerIdentity {
    private var cachedId: String? = null
    private var cachedDeviceInfo: DeviceInfo? = null

    fun getPlayerId(context: android.content.Context): String {
        cachedId?.let { return it }

        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()

        val raw = "${Build.MANUFACTURER}-${Build.MODEL}-$androidId"
        cachedId = UUID.nameUUIDFromBytes(raw.toByteArray()).toString()
        return cachedId!!
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
