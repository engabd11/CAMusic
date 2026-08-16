package com.engabd.sendpin.data

import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Whether the OS is currently exempting this app from battery optimisation — the
 * thing `SendspinConnectionService` needs to survive Doze and keep receiving
 * announcements while backgrounded.
 *
 * This is a live system check, not a persisted setting: OEM battery managers
 * (Samsung, Xiaomi, Oppo) can silently revoke the exemption after it was granted,
 * so "the user tapped through onboarding once" is not evidence it still holds.
 */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * [isIgnoringBatteryOptimizations], re-read every time this composable's lifecycle
 * owner resumes — which is what actually catches the case this exists for: the user
 * leaves for the OS battery settings screen (or Settings comes back from anywhere
 * else after the exemption was revoked in the background) and returns.
 */
@Composable
fun rememberIsIgnoringBatteryOptimizations(): Boolean {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = isIgnoringBatteryOptimizations(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return granted
}
