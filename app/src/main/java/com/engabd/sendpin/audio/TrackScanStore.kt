package com.engabd.sendpin.audio

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.security.MessageDigest

/**
 * Where scans live between plays.
 *
 * A small in-memory map in front of a directory of compact binary files, one per
 * track. A scan is expensive — a decode and a few seconds of DSP, and over a
 * network the decode is a download — so throwing it away when the song ends
 * would make the whole feature a tax on every listen instead of a one-off.
 *
 * Deliberately not `.npz`, which is what syncoV2 writes, and deliberately not
 * a database. The format below is a couple of hundred lines of `DataOutputStream`
 * that a phone can read in under a millisecond, and one file per track is what
 * makes "forget this one" and "forget all of them" trivial to implement and
 * impossible to get subtly wrong.
 *
 * Files live in `filesDir`, not `cacheDir`: the OS may clear the cache directory
 * under storage pressure, and silently discarding a pre-analysed library — which
 * the user may have spent an hour and a gigabyte of transfer building — is not a
 * reasonable thing to do behind their back. Deleting them is a button instead.
 *
 * Thread-safe. Reads and writes happen on background dispatchers; the memory map
 * is consulted from the render path.
 */
class TrackScanStore(private val dir: File) {

    private val memory = object : LinkedHashMap<String, TrackScan>(MEMORY_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackScan>) =
            size > MEMORY_ENTRIES
    }

    /**
     * Keys known to have no file, so a track that has never been scanned costs
     * one `File.exists()` per process rather than one per play.
     */
    private val misses = HashSet<String>()

    @Synchronized
    private fun remember(key: String, scan: TrackScan) {
        memory[key] = scan
        misses.remove(key)
    }

    /** The scan for [key] if it is already in memory. Never touches the disk. */
    @Synchronized
    fun peek(key: String): TrackScan? = memory[key]

    /** Whether [key] is known to have no scan, without going to the disk. */
    @Synchronized
    fun knownMissing(key: String): Boolean = key in misses

    /** The scan for [key], from memory or from disk. Null if there isn't one. */
    fun load(key: String): TrackScan? {
        peek(key)?.let { return it }
        synchronized(this) { if (key in misses) return null }
        val file = fileFor(key)
        if (!file.exists()) {
            synchronized(this) { misses.add(key) }
            return null
        }
        val scan = runCatching { read(file) }.getOrElse {
            Log.w(TAG, "Unreadable scan ${file.name}: ${it.message}")
            null
        }
        if (scan == null) {
            // Deleted, not merely skipped. A file that will not parse — truncated,
            // corrupt, or written by a format this build cannot read — is not
            // going to start parsing later, and leaving it there means the track
            // fails the same way on every single play and is never rescanned.
            file.delete()
            synchronized(this) { misses.add(key) }
            return null
        }
        remember(key, scan)
        return scan
    }

    fun save(key: String, scan: TrackScan) {
        dir.mkdirs()
        val target = fileFor(key)
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            DataOutputStream(tmp.outputStream().buffered()).use { write(it, scan) }
            // Renamed into place, so a reader never sees a half-written file and
            // an interrupted save leaves the previous scan intact.
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            remember(key, scan)
        } catch (e: Exception) {
            Log.w(TAG, "Could not save the scan for $key: ${e.message}")
            tmp.delete()
        }
    }

    /** Forget one track's scan, on disk and in memory. */
    fun delete(key: String) {
        fileFor(key).delete()
        synchronized(this) {
            memory.remove(key)
            misses.add(key)
        }
    }

    /** Forget every scan. Returns how many files went. */
    fun deleteAll(): Int {
        val files = dir.listFiles() ?: emptyArray()
        var gone = 0
        for (f in files) if (f.delete()) gone++
        synchronized(this) {
            memory.clear()
            misses.clear()
        }
        return gone
    }

    /** `(number of scans, bytes on disk)`. */
    fun usage(): Pair<Int, Long> {
        val files = dir.listFiles { f: File -> f.name.endsWith(SUFFIX) } ?: return 0 to 0L
        return files.size to files.sumOf { it.length() }
    }

    fun has(key: String): Boolean = peek(key) != null || fileFor(key).exists()

    private fun fileFor(key: String): File = File(dir, fileName(key))

    // ── The format ─────────────────────────────────────────────────────────

    private fun write(out: DataOutputStream, scan: TrackScan) {
        out.writeInt(MAGIC)
        out.writeInt(FORMAT)
        out.writeFloat(scan.durationS)
        out.writeFloat(scan.bpm)
        out.writeFloat(scan.confidence)
        out.writeInt(scan.downbeat)

        out.writeInt(scan.beats.size)
        for (t in scan.beats) out.writeFloat(t)
        // Accents are a 0..1 weight the eye reads as "how big is this hit". A
        // byte is 1/255 of that, which is well past what a bulb can show, and it
        // is the difference between a library of scans measured in megabytes and
        // one measured in tens of them.
        out.writeInt(scan.accents.size)
        for (a in scan.accents) out.writeByte(quantise(a))

        out.writeInt(scan.sections.size)
        for (s in scan.sections) {
            out.writeFloat(s.startS)
            out.writeFloat(s.endS)
            out.writeByte(quantise(s.energy))
        }

        val profile = scan.intensity
        out.writeBoolean(profile != null)
        if (profile != null) {
            out.writeFloat(profile.sigLo)
            out.writeFloat(profile.sigHi)
            out.writeFloat(profile.dynamics)
            out.writeFloat(profile.tilt)
            out.writeFloat(profile.tempo)
            out.writeFloat(profile.character)
            out.writeFloat(profile.curveRateHz)
            out.writeInt(profile.curve.size)
            for (v in profile.curve) out.writeByte(quantise(v))
        }

        out.writeInt(scan.melbankRef.size)
        for (v in scan.melbankRef) out.writeByte(quantise(v))
    }

    private fun read(file: File): TrackScan? = DataInputStream(file.inputStream().buffered()).use { input ->
        try {
            if (input.readInt() != MAGIC) return null
            val format = input.readInt()
            // One format so far. When a second arrives, read the old layouts
            // here rather than bumping past them — a user's pre-analysed library
            // is worth far more than the code saved by discarding it, and
            // rescanning it is hours of decoding they did not ask for. Anything
            // this build genuinely cannot read is deleted by the caller, so a
            // bump without a reader silently costs the whole library.
            if (format !in COMPATIBLE_FORMATS) return null

            val durationS = input.readFloat()
            val bpm = input.readFloat()
            val confidence = input.readFloat()
            val downbeat = input.readInt()

            val beats = FloatArray(input.readIntBounded(MAX_BEATS)) { input.readFloat() }
            val accents = FloatArray(input.readIntBounded(MAX_BEATS)) { dequantise(input.readByte()) }

            val sections = List(input.readIntBounded(MAX_SECTIONS)) {
                ScanSection(input.readFloat(), input.readFloat(), dequantise(input.readByte()))
            }

            val profile = if (input.readBoolean()) {
                val sigLo = input.readFloat()
                val sigHi = input.readFloat()
                val dynamics = input.readFloat()
                val tilt = input.readFloat()
                val tempo = input.readFloat()
                val character = input.readFloat()
                val rate = input.readFloat()
                val curve = FloatArray(input.readIntBounded(MAX_CURVE)) { dequantise(input.readByte()) }
                IntensityProfile(sigLo, sigHi, dynamics, tilt, tempo, character, curve, rate)
            } else null

            val melbankRef = FloatArray(input.readIntBounded(MELBANK_BINS)) { dequantise(input.readByte()) }

            TrackScan(
                durationS = durationS,
                bpm = bpm,
                confidence = confidence,
                beats = beats,
                accents = accents,
                downbeat = downbeat,
                sections = sections,
                intensity = profile,
                melbankRef = melbankRef,
            )
        } catch (e: EOFException) {
            null  // truncated: treat as absent and rescan
        }
    }

    /**
     * A length from a file, refused if it is implausible.
     *
     * A corrupt four bytes here would otherwise ask for a two-billion-element
     * array before anything else has a chance to notice the file is nonsense.
     */
    private fun DataInputStream.readIntBounded(limit: Int): Int {
        val n = readInt()
        if (n < 0 || n > limit) throw EOFException("length $n out of range")
        return n
    }

    private fun quantise(v: Float): Int = (unitF(v) * 255f + 0.5f).toInt()

    private fun dequantise(b: Byte): Float = (b.toInt() and 0xFF) / 255f

    companion object {
        private const val TAG = "TrackScanStore"

        /** "CAMS" — enough to reject a file that is not one of ours at all. */
        private const val MAGIC = 0x43414D53

        private const val FORMAT = 1
        private val COMPATIBLE_FORMATS = setOf(1)

        private const val SUFFIX = ".scan"

        /** Scans held in memory. Each is a few tens of kilobytes. */
        private const val MEMORY_ENTRIES = 24

        // Sanity ceilings for lengths read off disk. A twelve-minute track at
        // 190 BPM has under 2 300 beats; the curve is 10 Hz over the same cap.
        private const val MAX_BEATS = 8_000
        private const val MAX_SECTIONS = 512
        private const val MAX_CURVE = 16_000

        /**
         * A track id is not a filename — Music Assistant ids carry slashes and
         * colons — so the hash is the name and the id never touches the
         * filesystem. It also keeps every name the same length, which is what
         * makes the directory listing cheap.
         */
        fun fileName(key: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            val hex = StringBuilder(40)
            for (i in 0 until 20) hex.append("%02x".format(digest[i]))
            return "$hex$SUFFIX"
        }
    }
}
