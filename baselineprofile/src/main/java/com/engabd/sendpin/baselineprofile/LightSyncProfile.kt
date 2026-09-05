package com.engabd.sendpin.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Records the code paths for the Lights tab and the Ambience screen behind it.
 *
 * The heaviest Compose tree in the app that no profile covered. It is a lazy list
 * of a dozen sections — glass cards, four horizontally scrolling chip rows, a
 * palette of swatch tiles, seven tunable sliders behind a switch, five feature rows
 * each with an info chip — and none of its classes appear in the shipped profile, so
 * every one of them is interpreted on the first visit.
 *
 * Tolerates a phone with no bridge paired: the tab still composes, and what it
 * composes then (the "no bridge" card, the transport routing, the screen scaffold)
 * is exactly the path a new install takes.
 *
 * Run: `./gradlew :app:generateBaselineProfile`
 */
class LightSyncProfile {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun openLightsAndScroll() = rule.collect(packageName = "com.engabd.sendpin") {
        pressHome()
        startActivityAndWait()

        device.findObject(By.text("Lights"))?.click()
        device.waitForIdle()
        device.wait(Until.hasObject(By.scrollable(true)), 5_000)

        device.findObject(By.scrollable(true))?.let { list ->
            list.setGestureMargin(device.displayWidth / 5)
            // Far enough down to reach the tunables and the feature rows, which are
            // the part of this screen that is expensive to compose.
            repeat(4) {
                list.fling(Direction.DOWN)
                device.waitForIdle()
            }
            repeat(2) {
                list.fling(Direction.UP)
                device.waitForIdle()
            }
        }

        // The Ambience screen is its own destination off this tab, with its own
        // effect tiles and sliders.
        device.findObject(By.textContains("Ambience"))?.click()
        device.waitForIdle()
        device.wait(Until.hasObject(By.scrollable(true)), 3_000)
        device.findObject(By.scrollable(true))?.let { list ->
            list.setGestureMargin(device.displayWidth / 5)
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }
        device.pressBack()
        device.waitForIdle()
    }
}
