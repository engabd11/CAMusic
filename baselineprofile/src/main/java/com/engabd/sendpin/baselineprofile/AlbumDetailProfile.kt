package com.engabd.sendpin.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Records the code paths for opening an album detail screen from the library and
 * scrolling its track list.
 *
 * The album detail screen has its own Compose tree — a collapsing hero with
 * album art, a track list, action buttons (play all, shuffle, download) — that
 * is not exercised by the library scroll profile. The push transition (slide-in
 * animation) and the LazyColumn of tracks are both hot paths worth pre-compiling.
 *
 * Tolerates an empty library: if there are no albums to tap, the profile still
 * captures startup, library composition, and the navigation infrastructure.
 *
 * Run: `./gradlew :app:generateBaselineProfile`
 */
class AlbumDetailProfile {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun openAlbumAndScrollTracks() = rule.collect(packageName = "com.engabd.sendpin") {
        pressHome()
        startActivityAndWait()

        // Navigate to the Library tab if not already there.
        device.findObject(By.text("Library"))?.click()
        device.wait(Until.hasObject(By.scrollable(true)), 5_000)

        // Scroll the library to find album covers. The grid tiles are the
        // first scrollable elements; tapping one opens the album detail.
        val grid = device.findObject(By.scrollable(true))
        if (grid != null) {
            grid.setGestureMargin(device.displayWidth / 5)

            // Scroll down a bit to get past any category headers to the covers.
            repeat(2) {
                grid.fling(Direction.DOWN)
                device.waitForIdle()
            }

            // Try to tap the first clickable element in the grid area —
            // an album cover. The grid tiles are roughly 1/3 of the screen
            // width, so tapping at 1/6 width and 1/4 height targets the
            // first tile in the grid.
            val tileX = device.displayWidth / 6
            val tileY = device.displayHeight / 4
            device.click(tileX, tileY)
            device.waitForIdle()

            // If a detail screen opened, scroll its track list.
            device.wait(Until.hasObject(By.scrollable(true)), 3_000)
            device.findObject(By.scrollable(true))?.let { list ->
                list.setGestureMargin(device.displayWidth / 5)
                repeat(3) {
                    list.fling(Direction.DOWN)
                    device.waitForIdle()
                }
                list.fling(Direction.UP)
                device.waitForIdle()
            }

            // Press back to return to the library — exercises the pop transition.
            device.pressBack()
            device.waitForIdle()
        }
    }
}