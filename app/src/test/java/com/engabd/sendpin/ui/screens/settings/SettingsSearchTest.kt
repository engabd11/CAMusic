package com.engabd.sendpin.ui.screens.settings

import com.engabd.sendpin.ui.screens.SettingsSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Finding a control by what it is called, and every page being findable. */
class SettingsSearchTest {

    private fun first(query: String) = SettingsSearch.search(query).firstOrNull()?.title

    @Test
    fun `a control is found by its name, part of it, or the words people use`() {
        assertEquals("ReplayGain", first("replaygain"))
        assertEquals("ReplayGain", first("replay"))
        assertEquals("ReplayGain", first("normalise volume"))
        assertEquals("Equaliser", first("eq"))
        assertEquals("Equaliser", first("bass"))
        assertEquals("Hue Bridge", first("hue"))
        assertEquals("Crossfade", first("cross"))
        assertEquals("Output mode", first("hi-res"))
        assertEquals("How the driving controls appear", first("pip"))
        assertEquals("Last.fm", first("last.fm"))
        assertEquals("Theme", first("dark"))
    }

    @Test
    fun `every word typed has to match, and accents and case do not matter`() {
        assertTrue(SettingsSearch.search("hue zebra").isEmpty())
        assertEquals("Accent colour", first("ACCENT"))
        assertEquals("Équaliser".length, "Equaliser".length)
        assertEquals("Equaliser", first("équaliser"))
    }

    @Test
    fun `a blank query finds nothing rather than everything`() {
        assertTrue(SettingsSearch.search("").isEmpty())
        assertTrue(SettingsSearch.search("   ").isEmpty())
    }

    @Test
    fun `a title match ranks above a keyword match`() {
        // "Speed limit alert" has speed in its title; others only in keywords, if at all.
        assertEquals("Speed limit alert", first("speed"))
        val gesture = SettingsSearch.search("gesture").map { it.title }
        assertTrue("Swipe to skip" in gesture && "Shake, flip and tap" in gesture)
    }

    @Test
    fun `every settings page can be reached from search`() {
        val routed = SettingsSearch.ENTRIES.mapNotNull { it.route }.toSet()
        SettingsSection.entries.forEach { section ->
            assertTrue(SettingsSearch.ENTRIES.any { it.section == section }, "nothing found in $section")
            subPagesFor(section).forEach { page ->
                assertTrue(page.route in routed, "no entry opens ${page.title} ($section)")
            }
        }
        listOf(PICK_ROUTE, BRIDGE_ROUTE, HA_ROUTE, ANALYSIS_ROUTE, LISTEN_ROUTE).forEach {
            assertTrue(it in routed, "no entry opens $it")
        }
    }

    @Test
    fun `entries point at pages that belong to their section`() {
        SettingsSearch.ENTRIES.forEach { entry ->
            val route = entry.route ?: return@forEach
            val special = route in setOf(PICK_ROUTE, BRIDGE_ROUTE, HA_ROUTE, ANALYSIS_ROUTE, LISTEN_ROUTE)
            assertTrue(special || subPagesFor(entry.section).any { it.route == route }, "${entry.title} -> $route is not in ${entry.section}")
        }
    }
}
