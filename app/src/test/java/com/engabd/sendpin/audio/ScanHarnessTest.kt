package com.engabd.sendpin.audio

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The offline analyser run over **real music**, so its accuracy can be measured
 * rather than asserted.
 *
 * [TrackAnalysisTest] checks that each algorithm does what it claims against
 * synthetic signals — a click track has beats where the clicks are, novelty
 * peaks where the material changes. That is necessary and it is not sufficient:
 * a click track cannot say whether the key detector survives a mix with a kick
 * drum in it, and nearly every constant in [TrackAnalysis] and [KeyDetection]
 * was reasoned from the signal rather than measured against one. This is where
 * the measuring happens.
 *
 * Reads decoded audio from a directory named by `-Dcamusic.audio.dir` and
 * **skips itself when that property is absent**, which is the normal case: the
 * corpus is a few hundred megabytes of somebody's music library and has no
 * business in the repository or in CI. `tools/analysis-harness/decode.sh` writes
 * the format it wants — mono float32 little-endian at [ANALYSIS_SAMPLE_RATE],
 * which is exactly what [TrackScanner]'s `Pump` hands to [OfflineExtractor]
 * after its own downmix and resample, so the harness exercises the real path
 * from the first frame on without needing a decoder on the JVM.
 *
 * Results go to a TSV named by `-Dcamusic.harness.out`, one row per track, so
 * two runs either side of a change can be diffed. See
 * `tools/analysis-harness/README.md` for the checks it feeds.
 */
class ScanHarnessTest {

    @Test
    fun `scan every track in the corpus and report`() {
        val dir = System.getProperty(DIR_PROPERTY)?.let(::File)
        if (dir == null || !dir.isDirectory) {
            println("[harness] -D$DIR_PROPERTY unset or not a directory; skipping")
            return
        }
        val files = dir.listFiles { f: File -> f.name.endsWith(SUFFIX) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.isEmpty()) {
            println("[harness] no *$SUFFIX in $dir, run tools/analysis-harness/decode.sh first")
            return
        }
        val limit = System.getProperty(LIMIT_PROPERTY)?.toIntOrNull() ?: files.size

        val rows = StringBuilder().append(HEADER).append('\n')
        for (file in files.take(limit)) {
            val started = System.nanoTime()
            val result = scanOf(file)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val name = file.name.removeSuffix(SUFFIX)
            rows.append(row(name, result, elapsedMs)).append('\n')
            println("[harness] $name  ${summary(result)}  ($elapsedMs ms)")
        }

        System.getProperty(OUT_PROPERTY)?.let { out ->
            File(out).also { it.parentFile?.mkdirs() }.writeText(rows.toString())
            println("[harness] wrote $out")
        }
    }

    /**
     * Push one file through [OfflineExtractor] in fixed chunks and finish the scan.
     *
     * Streamed rather than read whole: a twelve-minute track is 60 MB of float32,
     * and the extractor exists to run at constant memory over a decoder's
     * arbitrary buffer sizes, so reading it all in first would exercise something
     * the app never does.
     */
    private fun scanOf(file: File): ScanResult {
        val ex = OfflineExtractor()
        val bytes = ByteArray(CHUNK * 4)
        val floats = FloatArray(CHUNK)
        file.inputStream().buffered().use { input ->
            while (true) {
                // Read to a whole number of samples: a buffered read may stop
                // mid-float, and carrying the remainder is not worth the code
                // when the loop can simply ask for the rest of it.
                var filled = 0
                while (filled < bytes.size) {
                    val n = input.read(bytes, filled, bytes.size - filled)
                    if (n <= 0) break
                    filled += n
                }
                if (filled < 4) break
                val samples = filled / 4
                for (i in 0 until samples) {
                    // Little-endian, as ffmpeg's f32le writes it and as
                    // DataInputStream would get backwards.
                    val bits = (bytes[i * 4].toInt() and 0xFF) or
                        ((bytes[i * 4 + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[i * 4 + 2].toInt() and 0xFF) shl 16) or
                        (bytes[i * 4 + 3].toInt() shl 24)
                    floats[i] = Float.fromBits(bits)
                }
                ex.push(floats, samples)
                if (filled < bytes.size) break
            }
        }
        return finishScan(ex)
    }

    private fun summary(result: ScanResult): String = when (result) {
        is ScanResult.Failed -> "FAILED ${result.reason} ${result.detail}"
        is ScanResult.Ok -> with(result.scan) {
            val k = key?.let {
                "${PITCH_NAMES[it.tonic]} ${it.mode.name.lowercase()} c=${fmt(it.confidence, 2)}"
            } ?: "-"
            "bpm=${fmt(bpm, 1)} conf=${fmt(confidence, 2)} beats=${beats.size} " +
                "bar=$beatsPerBar/db=$downbeat " +
                "sections=${sections.size}->${sections.map { it.label }.distinct().size} " +
                "key=$k tune=${fmt(tuningCents, 1)}c"
        }
    }

    private fun row(name: String, result: ScanResult, elapsedMs: Long): String {
        if (result is ScanResult.Failed) return listOf(name, "FAILED", result.reason.name).join()
        val scan = (result as ScanResult.Ok).scan
        val key = scan.key
        // Median inter-beat interval: a cross-check on `bpm` that does not depend
        // on how the scan chose to report it.
        val ibi = if (scan.beats.size > 1) {
            val gaps = FloatArray(scan.beats.size - 1) { scan.beats[it + 1] - scan.beats[it] }
            gaps.sorted()[gaps.size / 2]
        } else 0f
        return listOf(
            name,
            "OK",
            fmt(scan.bpm, 3),
            fmt(scan.confidence, 4),
            scan.beats.size.toString(),
            fmt(ibi, 4),
            scan.beatsPerBar.toString(),
            scan.downbeat.toString(),
            scan.sections.size.toString(),
            scan.sections.map { it.label }.distinct().size.toString(),
            scan.sections.joinToString(",") { it.label.toString() },
            key?.tonic?.toString() ?: "-",
            key?.mode?.name ?: "-",
            key?.let { fmt(it.confidence, 4) } ?: "-",
            fmt(scan.tuningCents, 1),
            fmt(scan.intensity?.character ?: 0f, 4),
            fmt(scan.intensity?.dynamics ?: 0f, 4),
            elapsedMs.toString(),
        ).join()
    }

    /**
     * A guard that runs with no corpus: reported beat times must be strictly
     * ascending and inside the analysed span.
     *
     * It lives here rather than in [TrackAnalysisTest] because sub-frame beat
     * refinement is what could break it, and an interpolated beat that overshot
     * its neighbour would be invisible in every other test while making
     * [TrackScan.gridAt]'s binary search return the wrong interval.
     */
    @Test
    fun `beat times stay ordered and inside the analysed span`() {
        val sr = ANALYSIS_SAMPLE_RATE
        val seconds = 30f
        val sig = FloatArray((sr * seconds).toInt())
        val period = 60f / 128f
        val envLen = (0.06f * sr).toInt()
        var t = 0.3f
        while (t < seconds) {
            val i = (t * sr).toInt()
            for (k in 0 until minOf(envLen, sig.size - i)) {
                sig[i + k] += (exp(-k.toFloat() / (0.015f * sr)) *
                    sin(2.0 * PI * 110.0 * k / sr)).toFloat()
            }
            t += period
        }
        val ex = OfflineExtractor()
        ex.push(sig)
        val scan = (finishScan(ex) as ScanResult.Ok).scan

        for (i in 1 until scan.beats.size) {
            assertTrue(
                scan.beats[i] > scan.beats[i - 1],
                "beat $i at ${scan.beats[i]} does not follow ${scan.beats[i - 1]}",
            )
        }
        assertTrue(scan.beats.first() >= 0f, "first beat is negative: ${scan.beats.first()}")
        assertTrue(
            scan.beats.last() <= scan.analysedS,
            "last beat ${scan.beats.last()} is past the analysed span ${scan.analysedS}",
        )
        assertTrue(abs(scan.bpm - 128f) < 3f, "expected ~128 BPM, got ${scan.bpm}")
    }

    private fun List<String>.join() = joinToString("\t")

    /** `String.format` without a locale argument turns 1.5 into "1,5" in half of Europe. */
    private fun fmt(v: Float, dp: Int) = String.format(java.util.Locale.ROOT, "%.${dp}f", v)

    private companion object {
        const val DIR_PROPERTY = "camusic.audio.dir"
        const val OUT_PROPERTY = "camusic.harness.out"
        const val LIMIT_PROPERTY = "camusic.harness.limit"
        const val SUFFIX = ".f32"
        const val CHUNK = 8192

        val PITCH_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        val HEADER = listOf(
            "track", "status", "bpm", "gridConf", "beats", "medianIbi",
            "beatsPerBar", "downbeat", "sections", "labels", "labelSeq",
            "tonic", "mode", "keyConf", "tuningCents", "character", "dynamics", "ms",
        ).joinToString("\t")
    }
}
