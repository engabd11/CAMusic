package com.engabd.sendpin.audio

import android.media.AudioDeviceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Saved curves, the curve per output, and telling outputs apart. */
class EqProfilesTest {

    private val shared = LocalDsp.Config(enabled = true)
    private val bassy = LocalDsp.Config(enabled = true, bands = LocalDsp.Config.defaultBands().map { it.copy(gainDb = 3f) })
    private val headphones = OutputKey("bt:AA:BB:CC:DD:EE:FF", "WH-1000XM5")

    @Test
    fun `the shared curve runs unless each output keeps its own`() {
        val profiles = EqProfiles(byOutput = mapOf(headphones.id to bassy))
        assertEquals(shared, EqSelection.effective(shared, profiles, headphones))
        assertFalse(EqSelection.editsOutput(profiles, headphones))
    }

    @Test
    fun `with a curve per output, an output runs its own, else the shared one`() {
        val profiles = EqProfiles(perOutput = true, byOutput = mapOf(headphones.id to bassy))
        assertEquals(bassy, EqSelection.effective(shared, profiles, headphones))
        assertEquals(shared, EqSelection.effective(shared, profiles, OutputKeys.SPEAKER))
        assertTrue(EqSelection.editsOutput(profiles, OutputKeys.SPEAKER))
    }

    @Test
    fun `an output's curve is stored with its name and can be forgotten`() {
        val profiles = EqProfiles(perOutput = true).withOutputCurve(headphones, bassy)
        assertEquals(bassy, profiles.byOutput[headphones.id])
        assertEquals("WH-1000XM5", profiles.outputNames[headphones.id])
        val forgotten = profiles.withoutOutputCurve(headphones.id)
        assertTrue(forgotten.byOutput.isEmpty())
        assertTrue(forgotten.outputNames.isEmpty())
    }

    @Test
    fun `saving under a name already used replaces that curve`() {
        val profiles = EqProfiles().withSaved("Commute", shared).withSaved("Studio", bassy).withSaved("commute ", bassy)
        assertEquals(listOf("Studio", "commute"), profiles.saved.map { it.name })
        assertEquals(bassy, profiles.saved.last().config)
        assertEquals(listOf("commute"), profiles.withoutSaved("Studio").saved.map { it.name })
    }

    @Test
    fun `saved curves are capped, oldest first out`() {
        var profiles = EqProfiles()
        repeat(EqProfiles.MAX_SAVED + 5) { profiles = profiles.withSaved("Curve $it", shared) }
        assertEquals(EqProfiles.MAX_SAVED, profiles.saved.size)
        assertEquals("Curve 5", profiles.saved.first().name)
    }

    @Test
    fun `profiles survive a round trip, and a curve saved before parametric existed reads as ten bands`() {
        val profiles = EqProfiles(perOutput = true).withSaved("Mine", bassy).withOutputCurve(headphones, shared)
        assertEquals(profiles, assertNotNull(EqProfiles.decode(EqProfiles.encode(profiles))))
        val old = """{"enabled":true,"bands":[],"preampDb":0.0,"autoPreamp":true}"""
        assertFalse(assertNotNull(LocalDsp.decode(old)).parametric)
    }

    @Test
    fun `outputs are told apart by kind, address and name`() {
        assertEquals(
            OutputKey("bt:AA:BB:CC:DD:EE:FF", "WH-1000XM5"),
            OutputKeys.of(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "aa:bb:cc:dd:ee:ff", "WH-1000XM5"),
        )
        // Withheld address (no nearby-devices permission): the name stands in.
        assertEquals("bt:WH-1000XM5", OutputKeys.of(AudioDeviceInfo.TYPE_BLE_HEADSET, "00:00:00:00:00:00", "WH-1000XM5").id)
        assertEquals("usb:BTD 700", OutputKeys.of(AudioDeviceInfo.TYPE_USB_HEADSET, "card=1;device=0", "BTD 700").id)
        assertEquals("wired", OutputKeys.of(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "", null).id)
        assertEquals(OutputKeys.SPEAKER, OutputKeys.of(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", "Pixel"))
    }

    @Test
    fun `music goes to a headset before the speaker, Bluetooth first`() {
        val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val wired = AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        val bt = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        val usb = AudioDeviceInfo.TYPE_USB_DEVICE
        assertEquals(bt, OutputKeys.preferred(listOf(speaker, wired, bt, usb)))
        assertEquals(usb, OutputKeys.preferred(listOf(speaker, wired, usb)))
        assertEquals(speaker, OutputKeys.preferred(listOf(speaker)))
    }
}
