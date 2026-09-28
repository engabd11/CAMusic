package com.engabd.sendpin.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals

class ArtUrlsTest {

    private val signed1 = "http://nd:4533/rest/getCoverArt.view?u=me&t=aaa111&s=salt1&v=1.16.1&c=CAMusic&id=al-9&size=600"
    private val signed2 = "http://nd:4533/rest/getCoverArt.view?u=me&t=bbb222&s=salt2&v=1.16.1&c=CAMusic&id=al-9&size=600"

    @Test
    fun `two signings of one Subsonic cover share a cache key`() {
        assertEquals(ArtUrls.cacheKey(signed1), ArtUrls.cacheKey(signed2))
        assertEquals("http://nd:4533/rest/getCoverArt.view?u=me&v=1.16.1&c=CAMusic&id=al-9&size=600", ArtUrls.cacheKey(signed1))
    }

    @Test
    fun `different covers and sizes keep different keys`() {
        assertEquals(false, ArtUrls.cacheKey(signed1) == ArtUrls.cacheKey(signed1.replace("al-9", "al-10")))
        assertEquals(false, ArtUrls.cacheKey(signed1) == ArtUrls.cacheKey(signed1.replace("size=600", "size=200")))
    }

    @Test
    fun `other servers' urls are their own key`() {
        val jf = "http://jf:8096/Items/abc/Images/Primary?maxWidth=600&api_key=k"
        assertEquals(jf, ArtUrls.cacheKey(jf))
    }

    private val ma = "http://ma:8097/imageproxy?provider=builtin&size=0&fmt=jpeg&path=%252Fa.jpg"

    @Test
    fun `a Music Assistant tile asks the proxy for about its own size`() {
        assertEquals("http://ma:8097/imageproxy?provider=builtin&size=256&fmt=jpeg&path=%252Fa.jpg", ArtUrls.sized(ma, 200))
        assertEquals(ma.replace("size=0", "size=128"), ArtUrls.sized(ma, 128))
    }

    @Test
    fun `no size, a huge size or another server leaves the url alone`() {
        assertEquals(ma, ArtUrls.sized(ma, null))
        assertEquals(ma, ArtUrls.sized(ma, 4000))
        assertEquals(signed1, ArtUrls.sized(signed1, 200))
    }
}
