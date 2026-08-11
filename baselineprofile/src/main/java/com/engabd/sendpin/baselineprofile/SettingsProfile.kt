package com.engabd.sendpin.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Records the code paths for navigating to Settings and scrolling through it.
 *
 * Settings is a distinct Compose tree from the library and Now Playing —
 * toggles, sliders, navigation to sub-pages, and the server configuration
 * forms. The toggles and sub-page transitions are composable trees that
 * are worth pre-compiling so the first visit to Settings is smooth.
 *
 * Run: `./gradlew :app:generateBaselineProfile`
 */
class SettingsProfile {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun openSettingsAndScroll() = rule.collect(packageName = "com.engabd.sendpin") {
        pressHome()
        startActivityAndWait()

        // Navigate to Settings.
        device.findObject(By.text("Settings"))?.click()
        device.waitForIdle()
        device.wait(Until.hasObject(By.scrollable(true)), 5_000)

        // Scroll through the settings list.
        device.findObject(By.scrollable(true))?.let { list ->
            list.setGestureMargin(device.displayWidth / 5)
            repeat(3) {
                list.fling(Direction.DOWN)
                device.waitForIdle()
            }
            list.fling(Direction.UP)
            device.waitForIdle()
        }
    }
}