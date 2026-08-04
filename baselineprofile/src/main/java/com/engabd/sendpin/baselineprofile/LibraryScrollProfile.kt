package com.engabd.sendpin.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Records which code the app actually runs on startup and while scrolling the library,
 * so that code is compiled ahead of time instead of being JIT-compiled on first use.
 *
 * This is the fix for "the first scroll is rough and then it settles": without a
 * profile, every Compose class the library touches is interpreted until the JIT catches
 * up, which is exactly the window a user spends forming an opinion about the app.
 *
 * Run it against a connected device:
 *
 *     ./gradlew :app:generateBaselineProfile
 *
 * The journeys below are deliberately the ones a user takes first — open the app, look
 * at the library, scroll it, open something. Profiling a path nobody walks costs binary
 * size and buys nothing.
 */
class LibraryScrollProfile {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupAndScrollLibrary() = rule.collect(packageName = "com.engabd.sendpin") {
        pressHome()
        startActivityAndWait()

        // The library needs a server to fill, and generation may run against a phone
        // with none reachable — so every step below tolerates an empty screen rather
        // than failing the run. A profile of the empty state is still a profile of
        // startup, navigation and the lazy grid.
        device.wait(Until.hasObject(By.scrollable(true)), 5_000)

        device.findObject(By.scrollable(true))?.let { grid ->
            grid.setGestureMargin(device.displayWidth / 5)
            repeat(3) {
                grid.fling(Direction.DOWN)
                device.waitForIdle()
            }
            grid.fling(Direction.UP)
            device.waitForIdle()
        }
    }
}
