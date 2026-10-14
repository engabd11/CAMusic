package com.engabd.sendpin.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A published release this install could move to: the APK for this build's flavour,
 * and enough of the release to say what it is.
 */
data class ReleaseInfo(
    /** "0.17.0" — the tag without its "v". */
    val version: String,
    /** The release notes' bold lead line, as plain text; empty when there is none. */
    val headline: String,
    /** The release page on GitHub, for the full notes. */
    val pageUrl: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Lower-case hex SHA-256 of the APK, from GitHub's asset digest; null if absent. */
    val sha256: String?,
)

/**
 * Reading GitHub's `releases/latest` answer. Kept free of Android so the parts that
 * decide *whether* to offer an update are unit tested; [AppUpdater] does the I/O.
 */
object ReleaseParsing {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The release in [body], with the APK for [flavor] ("mobile" or "tv").
     *
     * Null for a draft or a pre-release (`releases/latest` should never return one,
     * but a wrong answer here installs a beta on someone's phone), and for a release
     * with no APK for this flavour — a webOS-only fix, say — since there is nothing
     * this install could do with it. Assets are matched by the `-<flavor>.apk` suffix
     * every release has used (`camusic-v0.16.0-mobile.apk`).
     */
    fun parseLatest(body: String, flavor: String): ReleaseInfo? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (root.bool("draft") || root.bool("prerelease")) return null
        val tag = root.str("tag_name") ?: return null
        val asset = (root["assets"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("name").orEmpty().endsWith("-$flavor.apk", ignoreCase = true) }
            ?: return null
        val url = asset.str("browser_download_url") ?: return null
        return ReleaseInfo(
            version = tag.removePrefix("v").removePrefix("V"),
            headline = headline(root.str("body").orEmpty()),
            pageUrl = root.str("html_url") ?: "https://github.com/engabd11/CAMusic/releases/latest",
            apkUrl = url,
            apkSize = asset["size"]?.jsonPrimitive?.longOrNull ?: -1L,
            sha256 = asset.str("digest")
                ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.lowercase(),
        )
    }

    /**
     * Whether [remote] is a later version than [local].
     *
     * Numeric, part by part, so 0.10.0 is after 0.9.9 — a string comparison would say
     * otherwise. Anything after a '-' is a build label ("-freshtest"), not a version,
     * and is ignored. A version that does not parse is never "newer": offering an
     * install on a misread tag is the one mistake here that costs the user something.
     */
    fun isNewer(remote: String, local: String): Boolean {
        val r = parts(remote) ?: return false
        val l = parts(local) ?: return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parts(version: String): List<Int>? {
        val core = version.trim().removePrefix("v").removePrefix("V").substringBefore('-')
        if (core.isEmpty()) return null
        return core.split('.').map { it.toIntOrNull() ?: return null }
    }

    /**
     * The first paragraph of the release notes, without markdown.
     *
     * Every release opens with one bold sentence that says what it is for; that is the
     * line worth showing beside the button. Headings and tables come after it.
     */
    fun headline(markdown: String): String {
        val first = markdown.replace("\r\n", "\n").trim()
            .split("\n\n").firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
            ?: return ""
        return first
            .replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
            .replace("**", "").replace("__", "").replace("`", "")
            .replace('\n', ' ')
            .trim()
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull == true
}
