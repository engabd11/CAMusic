package com.engabd.sendpin.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Records the code paths exercised during a cold start — the window between the
 * system creating the process and the first frame being drawn.
 *
 * This is the profile that makes the app open fast. It captures the Application
 * onCreate, the first Compose composition, the theme resolution, and the nav
 * host setup — the classes every launch touches regardless of which screen the
 * user lands on.
 *
 * The other profiles (library, now playing, album, settings) capture *what the
 * user does after startup*; this one captures *startup itself* in isolation, so
 * the class loading and Compose runtime initialisation are pre-compiled even on
 * a fresh install where no screen has been visited yet.
 *
 * Run: `./gradlew :app:generateBaselineProfile`
 */
class StartupProfile {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(packageName = "com.engabd.sendpin") {
        pressHome()
        startActivityAndWait()
        // Wait for the app to be fully visible — this is the window that matters.
        device.wait(Until.hasObject(By.textContains("CAMusic")), 5_000)
        device.waitForIdle()
    }
}