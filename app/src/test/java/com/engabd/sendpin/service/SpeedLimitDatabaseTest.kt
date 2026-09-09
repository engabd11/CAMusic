package com.engabd.sendpin.service

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The wire format between `tools/build_speed_db.py` and the speed-limit reader.
 *
 * These two halves are written in different languages, live in different files,
 * and are only ever exercised together on a phone in a car in Victoria — which
 * is a bad place to discover that the byte order or the microdegree scale
 * disagree. Everything here is built the way the Python `pack()` builds it.
 */
class SpeedLimitDatabaseTest {

    /** Exactly what `pack()` emits: uint16 count, then count * (int32 lon, int32 lat). */
    private fun pack(vararg segments: List<Pair<Double, Double>>): ByteArray {
        val size = segments.sumOf { 2 + it.size * 8 }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        for (segment in segments) {
            buf.putShort(segment.size.toShort())
            for ((lon, lat) in segment) {
                buf.putInt(Math.round(lon * 1e6).toInt())
                buf.putInt(Math.round(lat * 1e6).toInt())
            }
        }
        return buf.array()
    }

    @Test
    fun `a single polyline round-trips with its coordinates in order`() {
        val blob = pack(listOf(144.960000 to -37.810000, 144.961000 to -37.810000))

        val segments = SpeedLimitDatabase.parseSegments(blob)

        assertEquals(1, segments.size)
        assertEquals(2, segments[0].size)
        // Longitude first, latitude second. Swapping them is the one mistake that
        // still produces plausible-looking numbers.
        assertEquals(144.960000, segments[0][0].first, 1e-6)
        assertEquals(-37.810000, segments[0][0].second, 1e-6)
        assertEquals(144.961000, segments[0][1].first, 1e-6)
    }

    @Test
    fun `a zone's polylines stay separate rather than joining end to end`() {
        // Two disjoint stretches of the same speed zone, a kilometre apart. Run
        // together they would describe a straight line across the gap, and a
        // driver on a different road between them would match this zone.
        val blob = pack(
            listOf(144.960000 to -37.810000, 144.961000 to -37.810000),
            listOf(144.970000 to -37.815000, 144.970000 to -37.816000),
        )

        val segments = SpeedLimitDatabase.parseSegments(blob)

        assertEquals(2, segments.size)
        assertEquals(144.960000, segments[0][0].first, 1e-6)
        assertEquals(144.970000, segments[1][0].first, 1e-6)
    }

    @Test
    fun `microdegrees keep enough precision to tell two lanes apart`() {
        // 1e-6 degrees of latitude is about 0.11 m. The match threshold is 30 m,
        // so the rounding has three orders of magnitude of headroom - but a
        // scale-factor slip would show up here first.
        val blob = pack(listOf(144.9601234 to -37.8109876, 144.9601244 to -37.8109876))

        val segments = SpeedLimitDatabase.parseSegments(blob)

        assertEquals(144.960123, segments[0][0].first, 5e-7)
        assertEquals(-37.810988, segments[0][0].second, 5e-7)
    }

    @Test
    fun `a truncated blob stops at the last whole segment instead of throwing`() {
        val whole = pack(
            listOf(144.960000 to -37.810000, 144.961000 to -37.810000),
            listOf(144.970000 to -37.815000, 144.970000 to -37.816000),
        )
        // Lop the last point off the second segment: its count still promises two.
        val truncated = whole.copyOf(whole.size - 8)

        val segments = SpeedLimitDatabase.parseSegments(truncated)

        // The first segment survives; the second is not guessed at.
        assertEquals(1, segments.size)
        assertEquals(144.960000, segments[0][0].first, 1e-6)
    }

    @Test
    fun `a one-point segment is dropped, having no length to measure against`() {
        val blob = pack(
            listOf(144.960000 to -37.810000),
            listOf(144.970000 to -37.815000, 144.970000 to -37.816000),
        )

        val segments = SpeedLimitDatabase.parseSegments(blob)

        assertEquals(1, segments.size)
        assertEquals(144.970000, segments[0][0].first, 1e-6)
    }

    @Test
    fun `an empty blob is no segments rather than an exception`() {
        assertTrue(SpeedLimitDatabase.parseSegments(ByteArray(0)).isEmpty())
        assertTrue(SpeedLimitDatabase.parseSegments(byteArrayOf(0x01)).isEmpty())
    }

    // ── Unpacking the bundled asset ──────────────────────────────────────────
    //
    // The two questions [expandAsset] answers from the bytes rather than from a
    // filename. A normal build hands it an already-gunzipped asset, so neither
    // branch is exercised on the happy path — which is exactly why they are worth
    // pinning here: a wrong answer to either shows up as "auto-detect just doesn't
    // work", on a phone, in a car, with nothing on screen to say why.

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    @Test
    fun `a gzipped stream is expanded`() {
        val payload = "SQLite format 3\u0000and then some".toByteArray()

        val out = SpeedLimitDatabase.decompressIfGzipped(ByteArrayInputStream(gzip(payload)))
            .use { it.readBytes() }

        assertArrayEquals(payload, out)
    }

    @Test
    fun `a plain stream is passed through untouched`() {
        // The case a normal build actually hits: AGP's asset merger has already
        // gunzipped the committed file, so what arrives is the database itself.
        val payload = "SQLite format 3\u0000plain".toByteArray()

        val out = SpeedLimitDatabase.decompressIfGzipped(ByteArrayInputStream(payload))
            .use { it.readBytes() }

        assertArrayEquals(payload, out)
    }

    @Test
    fun `a stream too short to hold a magic number is passed through, not dropped`() {
        val out = SpeedLimitDatabase.decompressIfGzipped(ByteArrayInputStream(byteArrayOf(0x1f)))
            .use { it.readBytes() }

        assertArrayEquals(byteArrayOf(0x1f), out)
    }

    @Test
    fun `a real database header is recognised`() {
        val file = temp.newFile()
        file.writeBytes("SQLite format 3\u0000".toByteArray())

        assertTrue(SpeedLimitDatabase.looksLikeSqlite(file))
    }

    @Test
    fun `an lfs pointer checked in instead of the database is not`() {
        // What a clone without `git lfs pull` leaves behind. It is caught at build
        // time today, but the check costs nothing and the failure it prevents —
        // a non-database file that `prepare` will never replace, because `prepare`
        // only re-expands when the file is *missing* — is permanent.
        val file = temp.newFile()
        file.writeBytes("version https://git-lfs.github.com/spec/v1\n".toByteArray())

        assertFalse(SpeedLimitDatabase.looksLikeSqlite(file))
    }

    @Test
    fun `a truncated expand is not mistaken for a database`() {
        val file = temp.newFile()
        file.writeBytes("SQL".toByteArray())

        assertFalse(SpeedLimitDatabase.looksLikeSqlite(file))
    }

    @Test
    fun `a file that is not there is not a database`() {
        assertFalse(SpeedLimitDatabase.looksLikeSqlite(File(temp.root, "nothing-here")))
    }
}
