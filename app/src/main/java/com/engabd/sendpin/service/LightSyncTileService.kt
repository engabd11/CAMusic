package com.engabd.sendpin.service

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.engabd.sendpin.R
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
 * Light Sync in the notification shade.
 *
 * Turning the lights off is the one thing that is always wanted *now* and never
 * wanted from inside the app: someone walks into the room, or a film starts, and
 * unlocking a phone to open a music app to reach a tab is four steps too many. A
 * Quick Settings tile is one.
 *
 * Deliberately only a toggle, and only over the **direct bridge** path. The Home
 * Assistant path's enabled flag lives in Home Assistant — it is per-area state on the
 * integration, not something this app owns — so a tile claiming to drive it would be
 * lying half the time. On that transport the tile says so and opens the app instead.
 *
 * @see AppSettings.lightSyncEnabled
 */
class LightSyncTileService : TileService() {

    /**
     * Its own scope rather than the process one: a tile is bound and unbound
     * constantly as the shade opens and closes, and a collector outliving the binding
     * would keep waking to update a `qsTile` that is null.
     */
    private var scope: CoroutineScope? = null
    private var watcher: Job? = null

    private val settings by lazy { AppSettings(applicationContext) }

    override fun onStartListening() {
        super.onStartListening()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        // Both, because the tile reads differently per transport: on/off on the direct
        // path, and a pointer at where the switch actually lives on the HA one.
        watcher = s.launch {
            combine(settings.lightSyncEnabled, settings.lightSyncMode) { on, mode -> on to mode }
                .collect { (on, mode) -> render(on, mode) }
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
            if (settings.lightSyncMode.first() != AppSettings.MODE_DIRECT) {
                // Nothing to toggle from here — the HA path's switch is Home
                // Assistant's. Open the app rather than doing nothing and looking broken.
                openApp()
                return@launch
            }
            val next = !settings.lightSyncEnabled.first()
            settings.setLightSyncEnabled(next)
            // The collector repaints, but only once DataStore has committed. Painting
            // now keeps the tile from lagging a beat behind the tap.
            render(next, AppSettings.MODE_DIRECT)
        }
    }

    /**
     * Open the app from the shade.
     *
     * Two overloads because the API changed under us: from API 34 the system wants a
     * `PendingIntent` so it can attribute the launch, and the `Intent` form it
     * replaced is deprecated there but is the only one that exists below it.
     *
     * Suppressed twice because the two toolchains flag it separately: `DEPRECATION`
     * is the Kotlin compiler's, `StartActivityAndCollapseDeprecated` is lint's, and
     * lint does not read the `SDK_INT >= 34` guard that already keeps the deprecated
     * call off the versions that would throw for it. `minSdk` is 31, so the branch
     * below is the only thing API 31–33 can call — it stays until `minSdk` reaches 34.
     */
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

    private fun render(on: Boolean, mode: String) {
        val tile = qsTile ?: return
        val direct = mode == AppSettings.MODE_DIRECT
        // Not STATE_UNAVAILABLE on the Home Assistant transport, which is what this
        // did: SystemUI makes an unavailable tile non-clickable, so the "open the app
        // instead" fallback below could never fire and HA users got exactly the dead
        // tile it exists to avoid. Inactive with a subtitle saying where the switch
        // actually lives keeps the tap working.
        tile.state = if (direct && on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.light_sync_tile_label)
        tile.subtitle = when {
            !direct -> getString(R.string.light_sync_tile_ha)
            on -> getString(R.string.light_sync_tile_on)
            else -> getString(R.string.light_sync_tile_off)
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_light_sync_tile)
        tile.updateTile()
    }
}
