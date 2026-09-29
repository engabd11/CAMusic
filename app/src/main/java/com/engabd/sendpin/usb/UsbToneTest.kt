package com.engabd.sendpin.usb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Milestone 2's proof: a quiet 1 kHz tone through CAMusic's own driver, two seconds in
 * every format the DAC offers, with what the DAC confirmed and what the stream measured.
 * Android loses the DAC for the length of the test and gets it back at the end.
 */
object UsbToneTest {

    private const val SECONDS = 2
    private const val CHUNK_FRAMES = 1024

    suspend fun run(context: Context, onProgress: (String) -> Unit = {}): String {
        val device = UsbDacProbe.audioDevices(context).firstOrNull() ?: return "No USB audio device is connected."
        if (!UsbDacProbe.requestPermission(context, device)) return "USB permission was not granted."
        return withContext(Dispatchers.IO) {
            val session = UsbAudioSession.open(context, device) ?: return@withContext "Could not open the DAC."
            val lines = mutableListOf("Test tone: 1 kHz at -20 dBFS, ${SECONDS}s per format, ${session.speedName}")
            try {
                for (alt in session.info.outputs) {
                    for (rate in alt.sampleRates.ifEmpty { listOf(44_100, 48_000) }) {
                        val label = "${alt.bitResolution}-bit ${UacReport.khz(rate)}"
                        onProgress("Playing $label…")
                        if (!session.configure(alt, rate)) {
                            lines += "$label: ${session.error}"
                            continue
                        }
                        val confirmed = session.confirmedRate
                        if (!session.start()) {
                            lines += "$label: ${session.error}"
                            continue
                        }
                        var frame = 0L
                        val total = rate.toLong() * SECONDS
                        val bytesPerFrame = alt.channels * alt.subslotBytes
                        while (frame < total) {
                            val n = minOf(CHUNK_FRAMES.toLong(), total - frame).toInt()
                            val pcm = UsbAudioMath.tone(n, frame, rate, alt.channels, alt.subslotBytes, alt.bitResolution)
                            var off = 0
                            while (off < pcm.size) {
                                val took = session.write(pcm, off, pcm.size - off)
                                off += took
                                if (took == 0) delay(5)
                            }
                            frame += n
                        }
                        // Let the queue drain before measuring.
                        while (session.queuedBytes() >= bytesPerFrame) delay(10)
                        delay(100)
                        val s = session.stats()
                        session.stopStream()
                        lines += buildString {
                            append("$label: DAC confirmed ${confirmed?.let(UacReport::khz) ?: "?"}")
                            if (s != null) {
                                append(", ${s.urbsCompleted} transfers, ${s.framesSent} frames")
                                append(", ${s.packetErrors} packet errors")
                                // Silence padded before the first write and after the last is
                                // expected; mid-stream it would be an underrun.
                                append(", ${s.silentFrames} padding frames")
                                if (s.lastErrno != 0L) append(", last error ${s.lastErrno}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                lines += "Stopped: ${e.message}"
            } finally {
                session.close()
                lines += "DAC handed back to Android."
            }
            lines.joinToString("\n")
        }
    }
}
