package com.engabd.sendpin.usb

/**
 * The USB DAC report as text: what the device says it can do, for a person to read and
 * for a bug report, plus the raw descriptors so a DAC we do not own can become a test
 * fixture. Pure, so its wording is tested rather than eyeballed.
 */
object UacReport {

    fun format(
        name: String,
        device: UacDevice?,
        raw: ByteArray?,
        /** UAC2 rates per clock source id, from a RANGE request; null when it could not be read. */
        clockRates: Map<Int, List<Int>>? = null,
        note: String? = null,
    ): String = buildString {
        appendLine("USB DAC: $name")
        if (note != null) appendLine(note)
        if (device == null) {
            if (raw != null) appendLine("No USB Audio Class control interface: not an audio device.")
        } else {
            appendLine("Id: %04x:%04x".format(device.vendorId, device.productId))
            appendLine("USB Audio Class: ${uacName(device.uacVersion)}")
            for (c in device.clockSources) {
                val rates = clockRates?.get(c.id)
                appendLine(
                    "Clock ${c.id}: ${c.type}, rate control ${c.frequencyControl}" +
                        when {
                            rates == null -> ", rates not readable while Android holds the DAC"
                            rates.isEmpty() -> ", reported no rates"
                            else -> ", rates ${rates.joinToString(" / ") { khz(it) }}"
                        },
                )
            }
            if (device.volumeUnits.isEmpty()) {
                appendLine("Hardware volume: none (the level is set on the DAC or the headphones)")
            } else {
                for (v in device.volumeUnits) {
                    val parts = listOfNotNull(
                        "master".takeIf { v.masterVolume },
                        "per channel".takeIf { v.channelVolume },
                        "mute".takeIf { v.mute },
                    )
                    appendLine("Hardware volume: unit ${v.id}, ${parts.joinToString(", ")}")
                }
            }
            if (device.outputs.isEmpty()) appendLine("Playback formats: none found")
            for (o in device.outputs) {
                val rates = when {
                    o.sampleRates.isNotEmpty() -> o.sampleRates.joinToString(" / ") { khz(it) }
                    o.sampleRateRange != null -> "${khz(o.sampleRateRange.first)}–${khz(o.sampleRateRange.last)}"
                    else -> "set on the clock"
                }
                val ep = o.endpoint
                appendLine(
                    "Format (interface ${o.interfaceNumber}, alt ${o.alternateSetting}): " +
                        "${o.channels} ch, ${o.bitResolution}-bit in ${o.subslotBytes * 8}-bit slots, $rates",
                )
                if (ep != null) {
                    appendLine(
                        "  Endpoint 0x%02x: %s, up to %d bytes x%d, interval %d%s".format(
                            ep.address, ep.sync, ep.maxPacketBytes, ep.transactionsPerMicroframe, ep.interval,
                            o.feedback?.let { ", feedback on 0x%02x".format(it.address) } ?: "",
                        ),
                    )
                }
            }
        }
        if (raw != null) {
            appendLine()
            appendLine("Raw descriptors (${raw.size} bytes):")
            raw.toList().chunked(32).forEach { line ->
                appendLine(line.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) })
            }
        }
    }.trimEnd()

    private fun uacName(v: Int) = when (v) {
        0x0100 -> "1.0"
        0x0200 -> "2.0"
        0x0300 -> "3.0"
        else -> "unknown (0x%04x)".format(v)
    }

    internal fun khz(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(java.util.Locale.ROOT, hz / 1000.0)
}
