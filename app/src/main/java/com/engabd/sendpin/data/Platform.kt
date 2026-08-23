package com.engabd.sendpin.data

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import com.engabd.sendpin.BuildConfig

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
    /**
     * True on the "tv" flavor *or* on any device reporting itself as a
     * television.
     *
     * The flavor half is the load-bearing one, and it is not redundant: the
     * permissions those phone-only features assume are stripped by
     * `app/src/tv/AndroidManifest.xml`, which is a build-time, per-flavor fact.
     * A device check alone would disagree with it on any box that does not set
     * `UI_MODE_TYPE_TELEVISION` (plenty of cheap Android TV sticks report
     * `NORMAL`, and so does a tv APK sideloaded onto a tablet) — leaving the
     * tv build starting monitors whose permissions it does not hold.
     *
     * The UI-mode half still earns its place: it lets the *mobile* APK, if
     * someone sideloads it onto a TV, skip the same features for the same
     * reason — there the permissions exist but the hardware behind them does not.
     */
    fun isTelevision(context: Context): Boolean =
        BuildConfig.FLAVOR == "tv" ||
            (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
                ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}
