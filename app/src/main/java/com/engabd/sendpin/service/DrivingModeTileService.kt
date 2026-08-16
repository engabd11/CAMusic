package com.engabd.sendpin.service

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.engabd.sendpin.R
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Driving mode in the notification shade.
 *
 * The manual half of the trigger, and the reason the Bluetooth half is allowed to be
 * the *only* automatic one. A car with no pairing, an aux cable, a hire car, or
 * simply wanting the bar up — all of them are one tap here rather than a reason to
 * go and build foreground-app detection.
 *
 * Modelled on [LightSyncTileService], including its hard-won lesson: a tile that
 * cannot act is [Tile.STATE_INACTIVE] with a subtitle saying why, never
 * `STATE_UNAVAILABLE`. SystemUI makes an unavailable tile non-clickable, so the
 * "open the app instead" fallback could never fire and the user got exactly the dead
 * tile the fallback exists to avoid.
 */
class DrivingModeTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var watcher: Job? = null

    private val settings by lazy { AppSettings(applicationContext) }
    private val driving by lazy { SendpinApp.instance.drivingMode }

    override fun onStartListening() {
        super.onStartListening()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        watcher = s.launch {
            combine(settings.drivingEnabled, driving.active) { enabled, active -> enabled to active }
                .collect { (enabled, active) -> render(enabled, active) }
        }
    }

    override fun onStopListening() {
        watcher?.cancel()
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val s = scope ?: return
        s.launch {
            if (!settings.drivingEnabled.first()) {
                // Nothing to toggle: the feature is off, and turning it on from here
                // would skip the permission and car-picker setup it needs. Open the
                // app where that conversation can happen.
                openApp()
                return@launch
            }
            driving.toggleManual()
            render(true, driving.active.value)
        }
    }

    /** See [LightSyncTileService.openApp] — the same two-API dance, for the same reason. */
    @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
    private fun openApp() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(openAppIntent(applicationContext, OpenAppRequest.TILE))
        } else {
            startActivityAndCollapse(
                android.content.Intent(applicationContext, com.engabd.sendpin.MainActivity::class.java)
                    .setAction(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    .setFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
            )
        }
    }

    private fun render(enabled: Boolean, active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.driving_tile_label)
        tile.subtitle = when {
            !enabled -> getString(R.string.driving_tile_disabled)
            active -> getString(R.string.driving_tile_on)
            else -> getString(R.string.driving_tile_off)
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_driving_tile)
        tile.updateTile()
    }
}
