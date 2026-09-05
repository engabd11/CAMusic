package com.engabd.sendpin.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Records the two screens reached from Settings that are lists rather than pages:
 * Downloads and Listening statistics.
 *
 * Both are their own Compose trees and neither appears in the shipped profile.
 * Statistics is the more expensive of the two by a distance — a dozen cards, most of
 * them drawing a chart on a Canvas — and it is the sort of screen someone opens once
 * out of curiosity, which is exactly when a slow first frame is the whole impression.
 *
 * Tolerates an empty library and no downloads: the empty states are on the same path.
 *
 * Run: `./gradlew :app:generateBaselineProfile`
 */
class DownloadsAndStatsProfile {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun openDownloadsAndStats() = rule.collect(packageName = "com.engabd.sendpin") {
        pressHome()
        startActivityAndWait()

        device.findObject(By.text("Settings"))?.click()
        device.waitForIdle()

        // Settings is an index of sections now, so both of these are two taps down:
        // System, Storage & About, then the page itself.
        openFromSystemSection("Downloads & storage", "Manage downloads")
        openFromSystemSection("About & statistics", "Listening statistics")
    }

    /**
     * Opens [page] under System, Storage & About and follows the row named [row]
     * into the screen behind it, then comes back to the Settings index.
     */
    private fun MacrobenchmarkScope.openFromSystemSection(
        page: String,
        row: String,
    ) {
        device.findObject(By.textContains("System, Storage"))?.click()
        device.waitForIdle()
        device.findObject(By.textContains(page))?.click()
        device.waitForIdle()
        device.findObject(By.textContains(row))?.click()
        device.waitForIdle()

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

        // Back out of the screen, then out of the page, to the index.
        repeat(3) {
            device.pressBack()
            device.waitForIdle()
        }
    }
}
