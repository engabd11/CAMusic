package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.audio.GestureKind
import com.engabd.sendpin.audio.GestureState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whether a detected gesture lands where the room actually is.
 *
 * The detector's own tests say a sweep was seen. These say the room *shows* it —
 * in the right order, in the right direction, and not at all in a room that
 * cannot express it. A gesture rendered backwards, or spread evenly over lamps
 * that should have lit one at a time, is the failure a user reports as
 * "flickering" rather than as a missing feature.
 */
class SyncoEngineGestureTest {

    private fun ch(id: Int, x: Float, y: Float, z: Float = 0f) =
        EntertainmentChannel(channelId = id, position = ChannelPosition(x, y, z))

    /** Six lamps in a row, left to right. */
    private val lineRoom = (0 until 6).map { ch(it, -1f + 0.4f * it, 0f) }

    /** Six lamps around the listener, ids in no particular spatial order. */
    private val ringRoom = (0 until 6).map { i ->
        val t = 2.0 * PI * i / 6
        ch(i, cos(t).toFloat(), sin(t).toFloat())
    }

    /** Two bulbs on a shelf. */
    private val shelf = listOf(ch(0, 0.10f, 0f), ch(1, 0.22f, 0f))

    /** Steady music with no beat: whatever moves is the gesture, not a kick. */
    private fun quietFrame(i: Int) = AnalysisFrame(
        bands = mapOf("sub_bass" to 0.3f, "bass" to 0.3f, "low_mid" to 0.3f, "mid" to 0.3f, "high" to 0.3f),
        energy = 0.4f,
        flux = 0.2f,
        tAudio = i * 0.02f,
        salience = 0.8f,
        onsetWidth = 0.5f,
        melbank = FloatArray(16) { 0.4f },
        pan = FloatArray(16),
        centroid = 0.35f,
    )

    private fun traversal(from: Float, to: Float, durationS: Float = 1.5f) = GestureState(
        id = 1L, kind = GestureKind.TRAVERSAL,
        fromPan = from, toPan = to, durationS = durationS, strength = 1f,
    )

    private fun engine(channels: List<EntertainmentChannel>, on: Boolean = true) =
        SyncoEngine(channels).also {
            it.mode = SyncMode.HIGH
            it.spatialGestures = on
        }

    /**
     * Drive [n] frames and record, per channel, the brightest it ever got and
     * when. Brightness is the max colour channel, which is what the encoder sends.
     */
    private fun sweep(
        eng: SyncoEngine,
        channels: List<EntertainmentChannel>,
        gesture: GestureState?,
        n: Int = 150,
    ): Pair<Map<Int, Float>, Map<Int, Float>> {
        val peak = HashMap<Int, Float>()
        val peakAt = HashMap<Int, Float>()
        // Warm the engine up first, so the gesture is measured against a settled
        // room rather than against the initial rise from black.
        for (i in 0 until 60) eng.render(quietFrame(i), 1f / 60f)
        for (i in 0 until n) {
            val out = eng.render(quietFrame(60 + i), 1f / 60f, gesture = gesture)
            for ((cid, c) in out) {
                val b = max(c.first, max(c.second, c.third))
                if (b > (peak[cid] ?: 0f)) { peak[cid] = b; peakAt[cid] = i / 60f }
            }
        }
        for (c in channels) peak.getOrPut(c.channelId) { 0f }
        return peak to peakAt
    }

    // ── Linear ────────────────────────────────────────────────────────────

    @Test
    fun `a room in a line is classified and lit as a line`() {
        assertEquals(RoomTopology.LINEAR, classifyTopology(lineRoom))
        val (_, peakAt) = sweep(engine(lineRoom), lineRoom, traversal(-1f, 1f))
        for (i in 1 until lineRoom.size) {
            assertTrue(
                peakAt.getValue(i) > peakAt.getValue(i - 1),
                "lamp $i peaked at ${peakAt[i]}s, not after lamp ${i - 1} at ${peakAt[i - 1]}s",
            )
        }
    }

    /** The sign error this feature is most likely to ship with. */
    @Test
    fun `a right to left sweep lights the room right to left`() {
        val (_, peakAt) = sweep(engine(lineRoom), lineRoom, traversal(1f, -1f))
        for (i in 1 until lineRoom.size) {
            assertTrue(
                peakAt.getValue(i) < peakAt.getValue(i - 1),
                "a right-to-left sweep lit lamp $i at ${peakAt[i]}s, before lamp ${i - 1} at ${peakAt[i - 1]}s",
            )
        }
    }

    // ── Ring ──────────────────────────────────────────────────────────────

    /**
     * A sound crossing in front of the listener must appear to cross in front of
     * them. The lamp behind — at three-quarters of a turn — must therefore be the
     * *last* one the sweep reaches, not one it passes through on the way.
     */
    @Test
    fun `a sweep round a ring goes past the front rather than the back`() {
        assertEquals(RoomTopology.RING, classifyTopology(ringRoom))
        val eng = engine(ringRoom)
        val (peak, peakAt) = sweep(eng, ringRoom, traversal(-1f, 1f))

        val positions = normalizePositions(ringRoom)
        val ring = fitRing(positions.values.toList())!!
        val azimuth = ringRoom.associate { it.channelId to azimuthOf(positions.getValue(it.channelId), ring) }

        // Front-ish lamps (azimuth near 0.25) must light up brighter than the
        // rear one (near 0.75), which the sweep never travels through.
        val front = azimuth.entries.minByOrNull { kotlin.math.abs(it.value - 0.25f) }!!.key
        val back = azimuth.entries.minByOrNull { kotlin.math.abs(it.value - 0.75f) }!!.key
        assertTrue(
            peak.getValue(front) > peak.getValue(back),
            "the rear lamp (${peak[back]}) lit as brightly as the front one (${peak[front]})",
        )
        // And the front lamp is reached partway through, not at the very start.
        val at = peakAt.getValue(front)
        assertTrue(at > 0.1f && at < 1.4f, "the front lamp peaked at ${at}s, outside the sweep")
    }

    // ── Cluster ───────────────────────────────────────────────────────────

    /**
     * Philips' own rule for `AreaEffect`: better to show nothing than to show it
     * in the wrong place. Two bulbs on a shelf cannot express "across the room",
     * and flickering between them reads as a fault.
     */
    @Test
    fun `two lamps on a shelf get no traversal at all`() {
        assertEquals(RoomTopology.CLUSTER, classifyTopology(shelf))
        val withGesture = sweep(engine(shelf), shelf, traversal(-1f, 1f)).first
        val without = sweep(engine(shelf), shelf, null).first
        for (c in shelf) {
            assertEquals(
                without.getValue(c.channelId), withGesture.getValue(c.channelId), 1e-6f,
                "a traversal changed lamp ${c.channelId} in a cluster room",
            )
        }
    }

    /** A swell has no direction, so a cluster can still show it — as a bloom. */
    @Test
    fun `a swell still blooms a cluster room`() {
        val swell = GestureState(id = 1L, kind = GestureKind.SWELL, durationS = 1.5f, strength = 1f)
        val withGesture = sweep(engine(shelf), shelf, swell).first
        val without = sweep(engine(shelf), shelf, null).first
        for (c in shelf) {
            assertTrue(
                withGesture.getValue(c.channelId) > without.getValue(c.channelId) + 0.01f,
                "a swell did not lift lamp ${c.channelId} (${withGesture[c.channelId]} vs ${without[c.channelId]})",
            )
        }
        // And it lifts them together, rather than picking an order among lamps
        // that have no room to be ordered in.
        val lift = shelf.map { withGesture.getValue(it.channelId) - without.getValue(it.channelId) }
        assertEquals(lift[0], lift[1], 0.02f, "a bloom lifted the two lamps by different amounts")
    }

    // ── The revert ────────────────────────────────────────────────────────

    /**
     * With the setting off the renderer must be byte-identical to what it was —
     * the same guarantee `colourTilt = 0` and `spatialCoupling = 0` already carry.
     */
    @Test
    fun `the setting off renders exactly as before`() {
        val off = sweep(engine(lineRoom, on = false), lineRoom, traversal(-1f, 1f)).first
        val none = sweep(engine(lineRoom, on = false), lineRoom, null).first
        for (c in lineRoom) {
            assertEquals(
                none.getValue(c.channelId), off.getValue(c.channelId), 0f,
                "a gesture reached the room with the setting off",
            )
        }
    }

    /** Extreme is a different renderer and returns before the gesture layer. */
    @Test
    fun `Extreme is untouched by gestures`() {
        val eng = engine(lineRoom).also { it.mode = SyncMode.EXTREME }
        val ref = engine(lineRoom).also { it.mode = SyncMode.EXTREME }
        for (i in 0 until 120) {
            val a = eng.render(quietFrame(i), 1f / 60f, gesture = traversal(-1f, 1f))
            val b = ref.render(quietFrame(i), 1f / 60f)
            for ((cid, c) in a) assertEquals(b.getValue(cid), c, "Extreme differed on lamp $cid at frame $i")
        }
    }

    // ── Safety ────────────────────────────────────────────────────────────

    /**
     * A gesture is a wash, not a flash.
     *
     * It does move the field — a sweep genuinely brightens the room and then
     * lets it go, and over a whole gesture that is one half-transition, which is
     * honest rather than a bug. What must never happen is the *rate*: every
     * individual step has to stay far under [FLASH_DELTA], so the room reads as a
     * smooth gradation rather than as something switching. Philips put the limit
     * for rapid brightness change at 5 Hz and the bridge relays at 25 Hz; a
     * gesture that moved a tenth of full scale between frames would be both.
     */
    @Test
    fun `a full-strength gesture washes rather than flashes`() {
        val safety = FieldSafety()
        val eng = engine(lineRoom)
        fun field(out: Map<Int, Rgb>) =
            out.values.map { max(it.first, max(it.second, it.third)) }.average().toFloat()

        for (i in 0 until 60) safety.process(eng.render(quietFrame(i), 1f / 60f), 1f / 60f)
        var worstCount = 0
        var worstStep = 0f
        var prev = field(eng.render(quietFrame(60), 1f / 60f))
        for (i in 0 until 150) {
            val out = eng.render(quietFrame(61 + i), 1f / 60f, gesture = traversal(-1f, 1f))
            safety.process(out, 1f / 60f)
            worstCount = max(worstCount, safety.flashesInWindow)
            val f = field(out)
            worstStep = max(worstStep, kotlin.math.abs(f - prev))
            prev = f
        }
        assertTrue(
            worstStep < 0.25f * FLASH_DELTA,
            "a gesture moved the field by $worstStep in one frame, a quarter of the flash threshold",
        )
        assertTrue(
            worstCount < MAX_FLASHES_PER_S,
            "a gesture alone filled $worstCount of the $MAX_FLASHES_PER_S flash budget",
        )
    }

    /** Whatever the gesture, the encoder still gets values it can send. */
    @Test
    fun `gestures stay inside the range the encoder requires`() {
        for (room in listOf(lineRoom, ringRoom, shelf)) {
            for (mode in SyncMode.entries) {
                val eng = engine(room).also { it.mode = mode }
                for (i in 0 until 200) {
                    val g = if (i == 10) traversal(-1f, 1f, durationS = 6f) else null
                    for ((cid, c) in eng.render(quietFrame(i), 1f / 60f, gesture = g)) {
                        for (v in listOf(c.first, c.second, c.third)) {
                            assertTrue(v in 0f..1f, "$mode lamp $cid emitted $v")
                        }
                    }
                }
            }
        }
    }

    /**
     * A gesture ends and leaves nothing behind.
     *
     * Compared against a second engine fed the identical frames rather than
     * against this engine's own earlier output: colour drift, the energy envelope
     * and the sustain bloom all keep moving on their own, so "the same lamp five
     * seconds later" is not the same value even with nothing happening.
     */
    @Test
    fun `a gesture retires and leaves the room where it found it`() {
        val eng = engine(lineRoom)
        val ref = engine(lineRoom)
        for (i in 0 until 60) {
            eng.render(quietFrame(i), 1f / 60f)
            ref.render(quietFrame(i), 1f / 60f)
        }
        eng.render(quietFrame(60), 1f / 60f, gesture = traversal(-1f, 1f, durationS = 1f))
        ref.render(quietFrame(60), 1f / 60f)

        var out: Map<Int, Rgb> = emptyMap()
        var refOut: Map<Int, Rgb> = emptyMap()
        for (i in 0 until 300) {
            out = eng.render(quietFrame(61 + i), 1f / 60f)
            refOut = ref.render(quietFrame(61 + i), 1f / 60f)
        }
        for ((cid, c) in out) {
            val r = refOut.getValue(cid)
            val b = max(c.first, max(c.second, c.third))
            val rb = max(r.first, max(r.second, r.third))
            assertEquals(rb, b, 1e-4f, "lamp $cid was still $b, not $rb, five seconds after the gesture ended")
        }
    }
}
