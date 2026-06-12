package com.engabd.sendpin.discovery

import android.os.Build
import android.provider.Settings
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

    fun getDeviceInfo(): DeviceInfo {
        cachedDeviceInfo?.let { return it }

        cachedDeviceInfo = DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            os = "Android ${Build.VERSION.SDK_INT}",
        )
        return cachedDeviceInfo!!
    }

    fun getDefaultPlayerName(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}"
            .replaceFirstChar { it.uppercase() }
    }
}
