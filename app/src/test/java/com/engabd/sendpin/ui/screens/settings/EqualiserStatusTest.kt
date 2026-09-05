package com.engabd.sendpin.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the equaliser page says it is doing.
 *
 * The whole point of the line is that this curve lives in the sink of the player *this
 * phone* runs, and three of the app's routes never go through it. A page that offers
 * ten sliders over one of those is claiming something the code does not do, which is
 * the failure mode this app keeps hitting — so each of those cases is pinned here.
 */
class EqualiserStatusTest {

    private fun status(
        maPlayerName: String? = null,
        maIsSelf: Boolean? = null,
        localActive: Boolean = false,
        mpdRemote: Boolean = false,
        processorsBypassed: Boolean = false,
    ) = equaliserStatus(maPlayerName, maIsSelf, localActive, mpdRemote, processorsBypassed)

    @Test
    fun `a local session with a live chain is the only case that claims to be working`() {
        val s = status(localActive = true)
        assertFalse(s.warn)
        assertTrue(s.line.contains("Shaping"))
    }

    @Test
    fun `nothing playing is ready, not a warning`() {
        // The curve is stored and will be used the moment something local starts, so
        // warning here would cry wolf on the ordinary case of opening the page.
        val s = status()
        assertFalse(s.warn)
    }

    @Test
    fun `Music Assistant on a remote speaker names the speaker and points at its DSP`() {
        val s = status(maIsSelf = false, maPlayerName = "Kitchen")
        assertTrue(s.warn)
        assertTrue(s.line.contains("Kitchen"))
        assertTrue(s.line.contains("card below"))
    }

    @Test
    fun `Music Assistant with no player name still reads as a sentence`() {
        val s = status(maIsSelf = false, maPlayerName = null)
        assertTrue(s.warn)
        assertTrue(s.line.contains("another speaker"))
    }

    @Test
    fun `Music Assistant streaming to this phone is still not the local chain`() {
        // The native Sendspin engine decodes this one — MediaCodec straight to Oboe,
        // with no media3 sink for an AudioProcessor to sit in. "It is playing on this
        // phone" is exactly the reason someone would expect the curve to apply.
        val s = status(maIsSelf = true, localActive = true)
        assertTrue(s.warn)
        assertTrue(s.line.contains("its own engine"))
    }

    @Test
    fun `MPD is decoded on the server, so there is nothing here to shape`() {
        val s = status(mpdRemote = true, localActive = true)
        assertTrue(s.warn)
        assertTrue(s.line.contains("MPD"))
    }

    @Test
    fun `a bypassed processor chain sends you to the output page`() {
        val s = status(localActive = true, processorsBypassed = true)
        assertTrue(s.warn)
        assertTrue(s.line.contains("Output & signal path"))
    }

    @Test
    fun `Music Assistant outranks a bypassed chain`() {
        // Both are true whenever someone streams MA to this phone on Pure output, and
        // only one of them is the reason. Naming the output mode there would send them
        // to change a setting that would not have helped.
        val s = status(maIsSelf = false, maPlayerName = "Kitchen", processorsBypassed = true)
        assertTrue(s.line.contains("Kitchen"))
        assertFalse(s.line.contains("Output & signal path"))
    }

    @Test
    fun `every case says something, and only the working ones are quiet`() {
        val cases = listOf(
            status(localActive = true),
            status(),
            status(maIsSelf = true),
            status(maIsSelf = false),
            status(mpdRemote = true),
            status(localActive = true, processorsBypassed = true),
        )
        for (s in cases) {
            assertTrue("a status line is never empty", s.line.isNotBlank())
            assertTrue("a status line is a sentence", s.line.trimEnd().endsWith("."))
            assertTrue(
                "only a curve that is doing nothing warns",
                s.warn == s.line.startsWith("Not running"),
            )
        }
    }
}
