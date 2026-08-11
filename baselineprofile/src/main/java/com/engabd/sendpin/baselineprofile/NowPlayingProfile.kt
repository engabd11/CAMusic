package com.engabd.sendpin.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Records the code paths exercised when the app starts and the user lands on the
 * Now Playing tab — the default start destination in tab layout.
 *
 * The Now Playing screen's Compose tree (cover art, transport buttons, seek bar,
 * lyrics pane) is distinct from the library's, and the classes it loads on first
 * composition are not covered by the library scroll profile. Without this profile,
 * the first time the app opens to Now Playing those classes are interpreted until
 * the JIT catches up — visible as a rough first frame and sluggish seek bar.
 *
 * Run: `./gradlew :app:generateBaselineProfile`
 */
class NowPlayingProfile {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupAndNowPlaying() = rule.collect(packageName = "com.engabd.sendpin") {
        pressHome()
        startActivityAndWait()

        // The app opens to the "Playing" tab in tab layout. Wait for the screen
        // to settle — the cover art and transport controls are the hot path.
        device.wait(Until.hasObject(By.textContains("CAMusic")), 3_000)
        device.waitForIdle()

        // The seek bar and transport controls are the interactable elements.
        // Tapping where they typically are exercises the click handlers and
        // the position-tracking composables even if nothing is playing.
        val middleY = device.displayHeight / 2
        val middleX = device.displayWidth / 2

        // Tap the centre of the screen — where the cover/transport area is.
        // This exercises the composable tree even in an empty/idle state.
        device.click(middleX, middleY)
        device.waitForIdle()

        // Swipe left on the now playing area — some layouts expose the queue
        // this way, and the swipe itself exercises the gesture handling.
        device.swipe(
            device.displayWidth - 50, middleY,
            50, middleY,
            10,
        )
        device.waitForIdle()
    }
}