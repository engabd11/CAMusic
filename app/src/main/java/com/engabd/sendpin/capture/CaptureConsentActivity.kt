package com.engabd.sendpin.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * The system consent dialog for screen/audio capture, and nothing else.
 *
 * A `MediaProjection` token can only come from an Activity result, and the thing that
 * consumes it is a foreground service that outlives every Activity in the app. A
 * transparent trampoline is the standard shape for that gap: it appears, asks, hands
 * the answer to [PlaybackCapture] and finishes, so a rotation or a process death
 * mid-flow has nothing of its own to lose.
 *
 * **Order matters on API 34+ and there is no compile-time signal if it is wrong.**
 * The typed foreground service has to be *running* before `getMediaProjection` is
 * called, or the platform throws `SecurityException`. So this starts the service and
 * hands it the token; the service does the rest, in order, in one place — see
 * [PlaybackCaptureService.onStartCommand].
 */
class CaptureConsentActivity : ComponentActivity() {

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            PlaybackCapture.pendingResultCode = result.resultCode
            PlaybackCapture.pendingResultData = result.data
            PlaybackCaptureService.start(this, result.resultCode, result.data!!)
        } else {
            PlaybackCapture.publish(PlaybackCapture.State.DENIED)
        }
        // No animation to suppress: the theme is translucent and this window is never
        // meant to be seen as a screen at all.
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            PlaybackCapture.publish(PlaybackCapture.State.DENIED)
            finish()
            return
        }
        runCatching { consent.launch(manager.createScreenCaptureIntent()) }
            .onFailure {
                PlaybackCapture.publish(PlaybackCapture.State.DENIED)
                finish()
            }
    }

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, CaptureConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .onFailure { PlaybackCapture.publish(PlaybackCapture.State.DENIED) }
        }
    }
}
