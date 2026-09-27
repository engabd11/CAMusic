package com.engabd.sendpin.crash

/**
 * Masks the credentials a line of log text can carry, leaving everything else intact.
 *
 * Every library server this app speaks to authenticates *in the URL* for the things
 * a player has to hand to something else — a stream to ExoPlayer, a cover to Coil:
 * Subsonic signs each request with `u`/`t`/`s` (and legacy `p`), Jellyfin and Emby
 * take `api_key`, Plex `X-Plex-Token`. A Subsonic token and its salt can be replayed,
 * so a URL in the log is a login in the log. And the log is not private: the debug
 * file ([DebugBundle]) carries the app's own logcat, and the About page asks people
 * to attach that file to a public GitHub issue.
 *
 * So the rule is applied twice: to the URLs this app logs on purpose, and to every
 * logcat line the debug file copies — the second because an exception message from
 * a library (OkHttp, media3) can carry a full request URL this code never chose to
 * log.
 *
 * Only the *values* go. The parameter names, the host and the path stay, because
 * they are what make a line diagnosable: "which server, which endpoint, which cover
 * shape" is the question the log exists to answer.
 */
object LogRedactor {

    const val MASK = "***"

    /**
     * Query parameters whose values are credentials. Matched exactly (case-insensitive),
     * so `status=` or `size=` are not caught by `s=`.
     */
    private val SECRET_PARAMS = listOf(
        "u", "t", "s", "p",                       // Subsonic auth
        "api_key", "apikey", "x-emby-token",      // Jellyfin / Emby
        "x-plex-token",                           // Plex
        "token", "access_token", "refresh_token", "password", "auth", "sig", "signature",
    )

    private val queryParam = Regex(
        "(?i)([?&;](?:" + SECRET_PARAMS.joinToString("|") { Regex.escape(it) } + """)=)[^&\s"'#;,)\]>]+""",
    )

    /** `Authorization: Bearer …`, `Bearer …` and the MediaBrowser header's `Token="…"`. */
    private val bearer = Regex("""(?i)(\bbearer\s+)[A-Za-z0-9._~+/=-]+""")
    private val headerToken = Regex("""(?i)(\btoken=")[^"]+""")

    /** [text] with every credential value replaced by [MASK]. */
    fun scrub(text: String): String {
        if (text.isEmpty()) return text
        var out = queryParam.replace(text) { it.groupValues[1] + MASK }
        out = bearer.replace(out) { it.groupValues[1] + MASK }
        out = headerToken.replace(out) { it.groupValues[1] + MASK }
        return out
    }

    /** [scrub] for a value that may be null — the shape most log call sites have. */
    fun url(url: String?): String = url?.let(::scrub) ?: "null"
}
