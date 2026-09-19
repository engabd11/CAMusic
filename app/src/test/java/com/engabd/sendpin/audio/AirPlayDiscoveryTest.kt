package com.engabd.sendpin.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayDiscoveryTest {

    @Test
    fun `a two-section ft is low word first`() {
        // An Apple TV 4K's _raop._tcp record. Bits 16 and 17 — AirPlay 2 and the
        // HomeKit PIN — live in the low word, which is the *first* section.
        val features = AirPlayDiscovery.parseFeatures("0x4A7FDFD5,0x3C177FDE")
        assertEquals(0x3C177FDE_4A7FDFD5L, features)
        assertTrue(features and 0x10000L != 0L)
        assertTrue(features and 0x20000L != 0L)
    }

    @Test
    fun `the spec's own example lands the high word in the high half`() {
        assertEquals(0x0000001E_5A7FFFF7L, AirPlayDiscovery.parseFeatures("0x5A7FFFF7,0x1E"))
    }

    @Test
    fun `a single section is the whole value`() {
        assertEquals(0x4A8F00L, AirPlayDiscovery.parseFeatures("0x4A8F00"))
        assertEquals(0x4A8F00L, AirPlayDiscovery.parseFeatures("4A8F00"))
    }

    @Test
    fun `garbage parses to nothing rather than throwing`() {
        assertEquals(0L, AirPlayDiscovery.parseFeatures("not hex"))
        assertEquals(0L, AirPlayDiscovery.parseFeatures(""))
    }
}
