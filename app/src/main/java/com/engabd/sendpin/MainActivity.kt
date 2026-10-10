package com.engabd.sendpin

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import com.engabd.sendpin.ui.screens.DrivingPipCard
import androidx.compose.ui.graphics.toArgb
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ui.theme.pageColorFor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.core.content.ContextCompat
import com.engabd.sendpin.ui.App
import kotlinx.coroutines.launch
import com.engabd.sendpin.SendpinApp
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Paint the launch window in the chosen theme's page colour before the first
        // frame. themes.xml can only hold one colour, and it cannot see a preference
        // stored in DataStore, so on a light theme the window would flash black for as
        // long as it takes Compose to draw. Read synchronously from the boot mirror —
        // see AppSettings.bootTheme.
        val settings = AppSettings(this)
        val systemDark = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val page = pageColorFor(settings.bootTheme, systemDark)
        window.setBackgroundDrawable(ColorDrawable(page.toArgb()))

        // Ask for the display's full gamut where there is one.
        //
        // Album art is what this app is mostly made of, and on a P3 panel a
        // wide-gamut window is the difference between a saturated red rendering at
        // sRGB's boundary and rendering at the panel's. Costs nothing where the
        // display is sRGB — `isScreenWideColorGamut` is false and the call is skipped
        // — and nothing in composition either: Compose keeps its own colour space and
        // this only widens what the surface may present.
        //
        // Deliberately *not* paired with wide-gamut palette extraction. `getPixels`
        // returns sRGB whatever the bitmap's colour space, so the extractor cannot see
        // wide values at all today — but the fix for that (an F16 decode read back
        // through `getColor`) would find nothing to read: cover art arrives from Music
        // Assistant, Navidrome and Jellyfin as JPEG, which is sRGB in practice. The
        // gamut worth widening is the one the app *paints* into, and that is this line.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            resources.configuration.isScreenWideColorGamut
        ) {
            window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        }

        // Transparent bars either way; the app draws under both. Which *icons* the
        // system paints there is set from Compose once the theme is known — see
        // SystemBars in ui/App.kt.
        val barStyle = if (page.luminance() > 0.5f) SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        else SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
        // The foreground playback service needs a visible notification on Android 13+.
        //
        // Only for an install that is already past onboarding. Asking cold, before the
        // first frame, put a system dialog in front of a user who had not yet seen what
        // the app *is* — and a denial there was never noticed, never explained and never
        // asked again, while all three foreground services quietly needed it. The wizard
        // asks with a reason, at the point the reason makes sense; this covers the
        // upgrade case, where onboarding has already been completed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            settings.hasCompletedOnboarding &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // A voice request that cold-started the app. Not on a recreation: the intent
        // is redelivered with the saved state and would play the same search again.
        if (savedInstanceState == null) {
            handleVoiceSearch(intent)
            handleShortcut(intent)
        }
        handleSharedShow(intent)
        com.engabd.sendpin.service.DrivingPip.registerControls(this, pipControls)
        watchDrivingForPip()
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            // The app stays composed underneath, so leaving PiP returns to exactly the
            // screen that was open; the card only covers it while the window is small.
            Box {
                App(windowSizeClass = windowSizeClass)
                if (inPip.value) DrivingPipCard(Modifier.fillMaxSize())
            }
        }
    }

    /** Whether the activity is currently the driving Picture-in-Picture window. */
    private val inPip = mutableStateOf(false)

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    /**
     * The activity is singleTask, so a voice request while it is already running
     * arrives here rather than in onCreate.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoiceSearch(intent)
        handleShortcut(intent)
        handleSharedShow(intent)
    }

    /**
     * A `camusic://show/…` link someone sent — see [com.engabd.sendpin.hue.ShowShare].
     * The show is added to the Lights tab's saved shows and said so; it is not applied,
     * because following a link must never change what the room is doing.
     */
    private fun handleSharedShow(intent: android.content.Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != com.engabd.sendpin.hue.ShowShare.SCHEME || data.host != com.engabd.sendpin.hue.ShowShare.HOST) return
        val show = com.engabd.sendpin.hue.ShowShare.parse(data.toString())
        if (show == null) {
            android.widget.Toast.makeText(this, "That light show link is damaged.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        // Consumed, so a rotation or a return to the activity does not add it twice.
        intent.data = null
        kotlinx.coroutines.MainScope().launch {
            com.engabd.sendpin.data.AppSettings(this@MainActivity).addSharedShow(show)
            android.widget.Toast.makeText(
                this@MainActivity, "Added the light show \u201C${show.name}\u201D. Find it on the Lights tab.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * `MEDIA_PLAY_FROM_SEARCH` — "Hey Google, play X in CAMusic". The manifest has
     * declared this filter for a long time, but nothing read it, so the request
     * opened the app and stopped there.
     */
    private fun handleVoiceSearch(intent: android.content.Intent?) {
        if (intent?.action != android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        (application as SendpinApp).playFromVoice(intent.getStringExtra(android.app.SearchManager.QUERY))
    }

    /** A shortcut from long-pressing the app icon; see SendpinApp.publishShortcuts. */
    private fun handleShortcut(intent: android.content.Intent?) {
        val app = application as SendpinApp
        when (intent?.action) {
            // The same as an empty "Hey Google, play on CAMusic": carry on with what
            // was playing, or the last queue.
            ACTION_RESUME -> app.playFromVoice(null)
            ACTION_OPEN_LIBRARY -> app.appRoute.value = "library"
            ACTION_OPEN_LIGHTS -> app.appRoute.value = "light_sync"
        }
    }

    /** Routes a Picture-in-Picture button to whichever player owns the session. */
    private val pipControls = com.engabd.sendpin.service.DrivingPip.ControlReceiver()

    /**
     * The one moment Picture-in-Picture can be entered.
     *
     * The platform requires the activity to be foreground at the instant it enters,
     * so there is no "start it from anywhere" — this callback, fired as the user
     * leaves for another app, is both the only legal moment and exactly when the bar
     * becomes useful. It is also why the flow is "open the app, then start
     * navigating" rather than the other way round, and why the overlay mechanism
     * exists behind it.
     *
     * [DrivingPip.maybeEnter] returns immediately unless driving mode is actually
     * active, so leaving the app normally does nothing.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        com.engabd.sendpin.service.DrivingPip.maybeEnter(this)
    }

    /**
     * Enter the floating window the moment driving mode turns on, if the app happens
     * to be in front.
     *
     * `onUserLeaveHint` alone was not enough. It fires only when the user leaves for
     * another app *themselves* — not when the car connects while the app is open, not
     * when something else pulls itself forward, not on screen-off. The documented flow
     * ("open Maps, then start driving") produces none of those, which is why the
     * floating window read as doing nothing at all. Watching [DrivingMode.active] adds
     * the case the callback cannot see, and the activity is resumed here by
     * construction, which is the platform's own precondition for the transition.
     */
    private fun watchDrivingForPip() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                (application as SendpinApp).drivingMode.active.collect { on ->
                    if (on) com.engabd.sendpin.service.DrivingPip.maybeEnter(this@MainActivity)
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipControls) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_RESUME = "com.engabd.sendpin.action.RESUME"
        const val ACTION_OPEN_LIBRARY = "com.engabd.sendpin.action.OPEN_LIBRARY"
        const val ACTION_OPEN_LIGHTS = "com.engabd.sendpin.action.OPEN_LIGHTS"
    }
}
