package com.engabd.sendpin.car

import androidx.media3.common.MediaMetadata
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The media-type mapping, which is the half of the car's row metadata that can be
 * checked without a car.
 *
 * Worth pinning because the failure is invisible from here and silent there: a row
 * whose type is wrong still draws, just with the wrong placeholder and the wrong
 * meaning to a voice query. Nothing in a screenshot would show it.
 *
 * The `Bundle`-building half of [CarContentStyle] is deliberately not tested — it
 * needs a real `android.os.Bundle`, and there is no Robolectric in this project.
 */
class CarContentStyleTest {

    @Test
    fun `each browsable type maps to its own media type`() {
        assertEquals(MediaMetadata.MEDIA_TYPE_ARTIST, CarContentStyle.mediaType("artist", true))
        assertEquals(MediaMetadata.MEDIA_TYPE_ALBUM, CarContentStyle.mediaType("album", true))
        assertEquals(MediaMetadata.MEDIA_TYPE_PLAYLIST, CarContentStyle.mediaType("playlist", true))
        assertEquals(MediaMetadata.MEDIA_TYPE_GENRE, CarContentStyle.mediaType("genre", true))
        assertEquals(MediaMetadata.MEDIA_TYPE_PODCAST, CarContentStyle.mediaType("podcast", true))
        assertEquals(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK, CarContentStyle.mediaType("audiobook", true))
    }

    @Test
    fun `each playable type maps to its own media type`() {
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, CarContentStyle.mediaType("track", false))
        assertEquals(MediaMetadata.MEDIA_TYPE_RADIO_STATION, CarContentStyle.mediaType("radio", false))
        assertEquals(
            MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE,
            CarContentStyle.mediaType("podcast_episode", false),
        )
        assertEquals(
            MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER,
            CarContentStyle.mediaType("chapter", false),
        )
    }

    @Test
    fun `an unknown type stays mixed rather than being guessed at`() {
        // The previous behaviour for everything, and still the honest answer for a
        // type this app has not seen — a browser draws a neutral placeholder rather
        // than confidently the wrong one.
        assertEquals(MediaMetadata.MEDIA_TYPE_MIXED, CarContentStyle.mediaType("sonata", false))
        assertEquals(MediaMetadata.MEDIA_TYPE_MIXED, CarContentStyle.mediaType(null, false))
    }

    @Test
    fun `an unknown browsable type is at least known to be a folder`() {
        // Browsability is not a guess — the tree already decided it — so an unknown
        // *folder* can still say that much, which is what gets it a folder icon.
        assertEquals(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, CarContentStyle.mediaType("crate", true))
        assertEquals(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, CarContentStyle.mediaType(null, true))
    }

    @Test
    fun `the style hint wire values are the ones Android Auto documents`() {
        // These are a protocol, not an internal enum: the car reads the integers.
        assertEquals(1, CarContentStyle.value(CarRowStyle.LIST))
        assertEquals(2, CarContentStyle.value(CarRowStyle.GRID))
        assertEquals(3, CarContentStyle.value(CarRowStyle.CATEGORY_LIST))
        assertEquals(4, CarContentStyle.value(CarRowStyle.CATEGORY_GRID))
    }

    @Test
    fun `the extras keys are the platform strings`() {
        assertEquals("android.media.browse.CONTENT_STYLE_SUPPORTED", CarContentStyle.KEY_SUPPORTED)
        assertEquals("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", CarContentStyle.KEY_BROWSABLE)
        assertEquals("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", CarContentStyle.KEY_PLAYABLE)
        assertEquals("android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT", CarContentStyle.KEY_SINGLE_ITEM)
        assertEquals("android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT", CarContentStyle.KEY_GROUP_TITLE)
    }
}
