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
 * Content is a placeholder until the TV nav/screens land (see the plan's phased
 * steps) — this step exists to prove the flavor/manifest plumbing actually gets
 * the app onto a TV launcher's home screen via LEANBACK_LAUNCHER, which is a
 * different, narrower claim than "the Activity runs when started directly."
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

        setContent {
            SendspinTheme {
                TvApp()
            }
        }
    }
}
