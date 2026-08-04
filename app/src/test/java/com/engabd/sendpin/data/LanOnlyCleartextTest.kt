package com.engabd.sendpin.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cost of getting this wrong is asymmetric, so both directions are tested.
 *
 * A false negative — calling a public host local — silently puts the user's server
 * passwords and Home Assistant token on the wire in the clear, which is the whole
 * thing this guard exists to stop. A false positive is worse day to day: it locks
 * someone out of their own NAS with an error about a security policy they never set.
 */
class LanOnlyCleartextTest {

    @Test
    fun `rfc1918 literals are local`() {
        listOf(
            "192.168.0.48",     // the common home range
            "192.168.1.1",
            "10.0.0.5",         // 10/8
            "10.255.255.254",
            "172.16.0.1",       // bottom of 172.16/12
            "172.31.255.254",   // top of it
            "127.0.0.1",        // loopback
            "169.254.10.1",     // link-local, when DHCP has not answered
            "100.64.0.1",       // RFC6598 carrier NAT — Tailscale and friends
        ).forEach { assertTrue(LanOnlyCleartext.isLocal(it), "$it should be local") }
    }

    @Test
    fun `neighbouring public ranges are not local`() {
        listOf(
            "172.15.0.1",       // just below 172.16/12
            "172.32.0.1",       // just above it
            "11.0.0.1",         // adjacent to 10/8
            "192.167.0.1",      // adjacent to 192.168/16
            "8.8.8.8",
            "1.1.1.1",
        ).forEach { assertFalse(LanOnlyCleartext.isLocal(it), "$it should not be local") }
    }

    @Test
    fun `single-label and home suffixes are local`() {
        listOf(
            "nas",                    // no dot: public DNS cannot resolve it
            "music",
            "homeassistant.local",    // mDNS, which is how this app discovers servers
            "ma.lan",
            "server.home",
            "box.internal",
        ).forEach { assertTrue(LanOnlyCleartext.isLocal(it), "$it should be local") }
    }

    @Test
    fun `public hostnames are not local`() {
        listOf(
            "music.example.com",
            "navidrome.myserver.net",
            // The suffix has to be the whole last label, not merely present in the
            // name — otherwise registering `notlocal.com` would be enough to opt out.
            "local.example.com",
            "lan.example.com",
        ).forEach { assertFalse(LanOnlyCleartext.isLocal(it), "$it should not be local") }
    }

    @Test
    fun `ipv6 loopback and unique local are local`() {
        listOf("::1", "[::1]", "fd00::1", "fe80::1").forEach {
            assertTrue(LanOnlyCleartext.isLocal(it), "$it should be local")
        }
    }

    @Test
    fun `a trailing dot does not defeat the suffix match`() {
        // A fully-qualified mDNS name arrives with the root label attached.
        assertTrue(LanOnlyCleartext.isLocal("homeassistant.local."))
        assertFalse(LanOnlyCleartext.isLocal("music.example.com."))
    }

    @Test
    fun `malformed hosts are not treated as local`() {
        listOf("", "999.999.999.999", "192.168.0", "192.168.0.1.5").forEach {
            assertFalse(LanOnlyCleartext.isLocal(it), "$it should not be local")
        }
    }
}
