package com.engabd.sendpin.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `server/state` delta rules for the metadata role (spec §server/state). */
class MetadataStateTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun el(s: String) = json.parseToJsonElement(s)

    @Test
    fun `absent fields are unchanged, null fields are cleared`() {
        val st = MetadataState()
        st.apply(el("""{"timestamp":1,"title":"T","artist":"A","album":"B","progress":{"track_progress":0,"track_duration":1000,"playback_speed":1000}}"""))
        // A seek: only progress (and the timestamp) change.
        assertTrue(st.apply(el("""{"timestamp":2,"progress":{"track_progress":500,"track_duration":1000,"playback_speed":1000}}""")))
        val m = st.snapshot(json)!!
        assertEquals("T", m.title)
        assertEquals("A", m.artist)
        assertEquals(500L, m.progress?.trackProgress)
        assertEquals(2L, m.timestamp)
        // The album is cleared, the rest stays.
        st.apply(el("""{"album":null}"""))
        assertNull(st.snapshot(json)!!.album)
        assertEquals("T", st.snapshot(json)!!.title)
    }

    @Test
    fun `nested objects are replaced whole, never deep-merged`() {
        val st = MetadataState()
        st.apply(el("""{"title":"T","progress":{"track_progress":10,"track_duration":1000,"playback_speed":1000}}"""))
        st.apply(el("""{"progress":{"track_progress":20,"track_duration":1000,"playback_speed":0}}"""))
        val p = st.snapshot(json)!!.progress!!
        assertEquals(20L, p.trackProgress)
        assertEquals(0L, p.speedMilli, "playback_speed 0 is paused, not normal speed")
        // A null nested object clears it.
        st.apply(el("""{"progress":null}"""))
        assertNull(st.snapshot(json)!!.progress)
    }

    @Test
    fun `a null role object clears everything and an absent key changes nothing`() {
        val st = MetadataState()
        st.apply(el("""{"title":"T"}"""))
        assertFalse(st.apply(null), "key absent")
        assertEquals("T", st.snapshot(json)!!.title)
        assertTrue(st.apply(JsonNull))
        assertTrue(st.isEmpty)
        assertNull(st.snapshot(json))
        assertFalse(st.apply(JsonNull), "already empty")
    }
}
