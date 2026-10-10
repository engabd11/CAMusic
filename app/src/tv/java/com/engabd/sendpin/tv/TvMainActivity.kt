package com.engabd.sendpin.tv

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ui.theme.SendspinTheme
import com.engabd.sendpin.ui.theme.ThemeChoice
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.engabd.sendpin.ui.theme.pageColorFor

/**
 * The TV entry point (see app/src/tv/AndroidManifest.xml's LEANBACK_LAUNCHER
 * intent-filter). Mirrors MainActivity.kt's boot-window/theme-paint pattern —
 * same reason for both: themes.xml can only hold one colour and can't see a
 * DataStore preference, so this reads the boot mirror synchronously before the
 * first frame — minus everything phone-only (Picture-in-Picture, driving-mode
 * PiP wiring, window-size-class calculation for a form factor that only ever
 * has one size).
 *
 * Content is [TvApp], which owns the rail and every screen behind it.
 */
class TvMainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settings = AppSettings(this)
        val systemDark = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val page = pageColorFor(settings.bootTheme, systemDark)
        window.setBackgroundDrawable(ColorDrawable(page.toArgb()))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            resources.configuration.isScreenWideColorGamut
        ) {
            window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        }

        val barStyle = if (page.luminance() > 0.5f) SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        else SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)

        // Same upgrade-case reasoning as MainActivity: only asked once onboarding has
        // already been completed, so a first-run install isn't met with a system
        // dialog before the app has explained why the playback notification exists.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            settings.hasCompletedOnboarding &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (savedInstanceState == null) handleVoiceSearch(intent)

        setContent {
            // The theme the TV's own Settings screen offers. This used to be a bare
            // SendspinTheme {}, i.e. always OLED black, so choosing Light or Dark in
            // Settings saved the choice and changed nothing on screen. The boot mirror
            // seeds the first frame so the window does not flash the default first.
            val themeKey by settings.theme.collectAsState(initial = settings.bootTheme)
            SendspinTheme(theme = ThemeChoice.from(themeKey)) {
                TvApp()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoiceSearch(intent)
    }

    /**
     * "Play ... on CAMusic", said to the TV's assistant. The phone activity reads this
     * request and the TV build removes that activity, so on a TV the request opened
     * nothing at all. Plays the best match, or carries on with what was playing when
     * nothing was named, and shows Now Playing.
     */
    private fun handleVoiceSearch(intent: android.content.Intent?) {
        if (intent?.action != android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        (application as com.engabd.sendpin.SendpinApp)
            .playFromVoice(intent.getStringExtra(android.app.SearchManager.QUERY))
        TvRequests.tab.value = TvTab.NOW_PLAYING
    }
}
