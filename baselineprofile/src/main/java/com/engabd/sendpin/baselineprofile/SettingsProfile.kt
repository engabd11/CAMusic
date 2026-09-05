package com.engabd.sendpin.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Records the code paths for Settings: the index, a section, and a page inside it.
 *
 * Settings is a distinct Compose tree from the library and Now Playing — toggles,
 * sliders, segmented rows, status panels and the server forms — and none of it is in
 * the shipped profile.
 *
 * It drills two levels in rather than only flinging the index, which is what this
 * did. Every section is a menu now, so the index composes six NavRows and nothing
 * else: a profile that stops there records the one cheap screen in Settings and none
 * of the expensive ones behind it.
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

        // Scroll the index.
        scrollHere()

        // Then two levels in, on the section with the most controls behind it: the
        // output card alone carries the signal-path readout, three segmented rows and
        // the device capability panel.
        device.findObject(By.textContains("Audio Engine"))?.click()
        device.waitForIdle()
        device.findObject(By.textContains("Output & signal path"))?.click()
        device.waitForIdle()
        scrollHere()

        // Back up through both levels, which is the pop path.
        device.pressBack()
        device.waitForIdle()

        // A second section, for the cards the first does not reach — chips, swatches
        // and the theme picker.
        device.findObject(By.textContains("Interface & Appearance"))?.click()
        device.waitForIdle()
        device.findObject(By.textContains("Theme & accent"))?.click()
        device.waitForIdle()
        scrollHere()

        device.pressBack()
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }

    /** Fling whatever list is on screen, both ways. */
    private fun MacrobenchmarkScope.scrollHere() {
        device.wait(Until.hasObject(By.scrollable(true)), 5_000)
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