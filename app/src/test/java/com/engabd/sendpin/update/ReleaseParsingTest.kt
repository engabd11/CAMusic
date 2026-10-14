package com.engabd.sendpin.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The half of the in-app update that decides whether to offer one. The load-bearing
 * pins: the right flavour's APK is chosen, a pre-release is never offered, versions
 * compare numerically, and anything unreadable is never "newer".
 */
class ReleaseParsingTest {

    // Shaped like GitHub's real `releases/latest` answer for v0.16.0, trimmed.
    private fun release(
        tag: String = "v0.16.0",
        prerelease: Boolean = false,
        draft: Boolean = false,
        body: String = "**Your servers find themselves, and the app is a fifth smaller.** More below.\\n\\n## Downloads\\n| a | b |",
        assets: String = """
            {"name":"camusic-v0.16.0-mobile.apk","size":68765123,
             "browser_download_url":"https://github.com/engabd11/CAMusic/releases/download/v0.16.0/camusic-v0.16.0-mobile.apk",
             "digest":"sha256:94A16DC361FE057FA707E0C97189676F18DB87F14025785071C2CE25815EA683"},
            {"name":"camusic-v0.16.0-tv.apk","size":15932235,
             "browser_download_url":"https://github.com/engabd11/CAMusic/releases/download/v0.16.0/camusic-v0.16.0-tv.apk"},
            {"name":"camusic-v0.16.0-webos.ipk","size":41410,
             "browser_download_url":"https://github.com/engabd11/CAMusic/releases/download/v0.16.0/camusic-v0.16.0-webos.ipk"}
        """,
    ) = """
        {"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,
         "html_url":"https://github.com/engabd11/CAMusic/releases/tag/$tag",
         "body":"$body","assets":[$assets]}
    """

    @Test
    fun `the mobile build gets the mobile apk with its size and digest`() {
        val r = assertNotNull(ReleaseParsing.parseLatest(release(), "mobile"))
        assertEquals("0.16.0", r.version)
        assertTrue(r.apkUrl.endsWith("camusic-v0.16.0-mobile.apk"))
        assertEquals(68765123L, r.apkSize)
        assertEquals("94a16dc361fe057fa707e0c97189676f18db87f14025785071c2ce25815ea683", r.sha256, "lower-cased, prefix dropped")
        assertEquals("https://github.com/engabd11/CAMusic/releases/tag/v0.16.0", r.pageUrl)
    }

    @Test
    fun `the tv build gets the tv apk, and a missing digest is null rather than empty`() {
        val r = assertNotNull(ReleaseParsing.parseLatest(release(), "tv"))
        assertTrue(r.apkUrl.endsWith("-tv.apk"))
        assertNull(r.sha256)
    }

    @Test
    fun `a pre-release, a draft, or a release without this flavour is not offered`() {
        assertNull(ReleaseParsing.parseLatest(release(prerelease = true), "mobile"))
        assertNull(ReleaseParsing.parseLatest(release(draft = true), "mobile"))
        val webosOnly = release(assets = """{"name":"camusic-v0.16.1-webos.ipk","size":1,"browser_download_url":"https://x/y.ipk"}""")
        assertNull(ReleaseParsing.parseLatest(webosOnly, "mobile"))
    }

    @Test
    fun `an answer that is not json is no release`() {
        assertNull(ReleaseParsing.parseLatest("<html>rate limited</html>", "mobile"))
        assertNull(ReleaseParsing.parseLatest("", "mobile"))
    }

    @Test
    fun `versions compare by number, not by string`() {
        assertTrue(ReleaseParsing.isNewer("0.16.1", "0.16.0"))
        assertTrue(ReleaseParsing.isNewer("0.10.0", "0.9.9"))
        assertTrue(ReleaseParsing.isNewer("1.0", "0.99.99"))
        assertTrue(ReleaseParsing.isNewer("v0.17.0", "0.16.0"))
        assertFalse(ReleaseParsing.isNewer("0.16.0", "0.16.0"))
        assertFalse(ReleaseParsing.isNewer("0.15.3", "0.16.0"))
        assertFalse(ReleaseParsing.isNewer("0.16", "0.16.0"), "a missing part is zero")
    }

    @Test
    fun `a build label is ignored and an unreadable version is never newer`() {
        assertFalse(ReleaseParsing.isNewer("0.16.0", "0.16.0-freshtest"))
        assertTrue(ReleaseParsing.isNewer("0.16.1", "0.16.0-freshtest"))
        assertFalse(ReleaseParsing.isNewer("nightly", "0.16.0"))
        assertFalse(ReleaseParsing.isNewer("0.17.0", "dev"))
        assertFalse(ReleaseParsing.isNewer("", "0.16.0"))
    }

    @Test
    fun `the headline is the notes' first paragraph as plain text`() {
        val r = assertNotNull(ReleaseParsing.parseLatest(release(), "mobile"))
        assertEquals("Your servers find themselves, and the app is a fifth smaller. More below.", r.headline)
        assertEquals("See the docs.", ReleaseParsing.headline("## Heading\n\nSee [the docs](https://x).\n\nMore"))
        assertEquals("", ReleaseParsing.headline(""))
    }
}
