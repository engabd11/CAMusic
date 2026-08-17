package com.engabd.sendpin.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.ui.screens.DrivingBar

/**
 * The driving bar as a real overlay window — option E1.
 *
 * `TYPE_APPLICATION_OVERLAY` through `WindowManager`, which is the only route that
 * gives genuinely driving-sized targets and free positioning. It costs
 * `SYSTEM_ALERT_WINDOW`, a permission the user has to grant through a Settings
 * screen, which is why Picture-in-Picture is the default and this is the upgrade.
 *
 * ## The gotcha, which bites everyone once
 *
 * A `ComposeView` in a `WindowManager` overlay **crashes** unless the view tree is
 * given a [LifecycleOwner], a [SavedStateRegistryOwner] and a [ViewModelStoreOwner].
 * An Activity supplies all three and a Service supplies none, so this class is one —
 * see the three `setViewTree*Owner` calls below. It is not hard; it is invisible
 * until it throws.
 *
 * The lifecycle has to reach `RESUMED` before the view is added, and be walked down
 * to `DESTROYED` on the way out, or Compose's own effects never start and never
 * clean up.
 *
 * ## What it does not do
 *
 * No `START_STICKY`. A bar that resurrected itself after the system killed it would
 * appear over whatever the user was doing next, with no memory of a drive. If the
 * process goes, the drive is over as far as this is concerned; the car reconnecting
 * brings it back.
 */
class DrivingOverlayService :
    Service(),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    companion object {
        fun start(context: Context) {
            // Silently do nothing without the grant rather than crashing on
            // `addView`. The Settings screen is where the user is asked, once, with
            // a reason — see DrivingSettings.
            if (!canDrawOverlay(context)) return
            runCatching { context.startService(Intent(context, DrivingOverlayService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DrivingOverlayService::class.java)) }
        }

        fun canDrawOverlay(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    private var overlay: ComposeView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlay == null) addOverlay()
        return START_NOT_STICKY
    }

    private fun addOverlay() {
        if (!canDrawOverlay(this)) { stopSelf(); return }
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val owner = SendpinApp.instance.playbackOwner
        val driving = SendpinApp.instance.drivingMode

        val view = ComposeView(this).apply {
            // The three owners a ComposeView needs and a Service does not have.
            setViewTreeLifecycleOwner(this@DrivingOverlayService)
            setViewTreeSavedStateRegistryOwner(this@DrivingOverlayService)
            setViewTreeViewModelStoreOwner(this@DrivingOverlayService)
            setContent {
                DrivingBar(
                    onPlayPause = owner::playPause,
                    onNext = owner::next,
                    onPrevious = owner::previous,
                    onShuffle = owner::toggleShuffle,
                    onDismiss = { driving.setManual(false) },
                )
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // NOT_FOCUSABLE so the map underneath keeps the keyboard and the back
            // gesture — an overlay that stole focus would break navigation while
            // someone was navigating. Taps still reach it; only focus does not.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Bottom edge by default, because that is where a cradled phone's
            // controls fall under a thumb and where a map's own chrome is thinnest.
            // The bar can be dragged to the top — see DrivingBar.
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        runCatching { wm.addView(view, params) }
            .onSuccess { overlay = view }
            .onFailure { stopSelf() }
    }

    override fun onDestroy() {
        overlay?.let { view ->
            runCatching {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
            }
        }
        overlay = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        super.onDestroy()
    }
}
