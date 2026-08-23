package com.engabd.sendpin.data

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

/**
 * Distinguishes the TV flavor's runtime behaviour from the phone's, for the
 * handful of things in [com.engabd.sendpin.SendpinApp] that only make sense on
 * a phone (driving mode, USB DAC toasts, call-pause, speed alerts) and would
 * otherwise touch a permission the "tv" flavor's manifest no longer declares.
 *
 * A runtime check rather than a second flavor-scoped Application subclass: the
 * two flavors' manifests already point at the same `android:name=".SendpinApp"`,
 * and everything else `SendpinApp.onCreate()` does — the whole playback/light-
 * sync pipeline — is exactly what the TV build needs too, so forking the class
 * would mean keeping two copies of that in sync for no benefit.
 */
object Platform {
    fun isTelevision(context: Context): Boolean =
        (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
            ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}
