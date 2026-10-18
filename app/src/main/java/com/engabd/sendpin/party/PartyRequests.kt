package com.engabd.sendpin.party

/** What a request path asks for, once the party token has been checked. */
sealed interface PartyRoute {
    /** One of the few static files the party ships. [name] is always a key of [PartyPaths.ASSETS]. */
    data class Asset(val name: String) : PartyRoute

    /** An API call: `join`, `state`, `search`, `add`, `skip`, or `art` with an [arg]. */
    data class Api(val name: String, val arg: String? = null) : PartyRoute

    /** Anything else: wrong token, unknown file, traversal, directory listing. */
    data object NotFound : PartyRoute
}

/**
 * Request validation: paths, names and sizes. Pure, so every rule is a unit test.
 *
 * The server only ever answers `/p/<token>/...`; everything else is a plain 404 that
 * says nothing about whether the token or the file was the problem.
 */
object PartyPaths {
    val ASSETS = mapOf(
        "index.html" to "text/html; charset=utf-8",
        "app.js" to "text/javascript; charset=utf-8",
        "app.css" to "text/css; charset=utf-8",
    )
    val API = setOf("join", "state", "search", "add", "skip", "art")

    private val ID = Regex("[A-Za-z0-9]{8,64}")
    private val SPACES = Regex(" +")

    fun route(uri: String, token: String): PartyRoute {
        if (uri.length > MAX_URI) return PartyRoute.NotFound
        // Checked on the raw text, before any decoding, so an encoded dot cannot sneak
        // past: a real client never needs a dot-dot, a backslash, a NUL or a percent
        // sign inside the path.
        val path = uri.substringBefore('?')
        if (path.contains("..") || path.contains('\\') || path.contains('\u0000') || path.contains('%')) return PartyRoute.NotFound
        val parts = path.split('/')
        // "", "p", token, rest...
        if (parts.size < 3 || parts[0] != "" || parts[1] != "p") return PartyRoute.NotFound
        if (!PartyAuth.matches(token, parts[2])) return PartyRoute.NotFound
        val rest = parts.drop(3)
        return when {
            rest.isEmpty() -> PartyRoute.NotFound
            // "/p/<token>/" is the page itself (an empty last segment).
            rest == listOf("") -> PartyRoute.Asset("index.html")
            rest.size == 1 && rest[0] in ASSETS -> PartyRoute.Asset(rest[0])
            rest.size in 2..3 && rest[0] == "api" && rest[1] in API -> {
                val arg = rest.getOrNull(2)
                if (arg != null && !ID.matches(arg)) PartyRoute.NotFound else PartyRoute.Api(rest[1], arg)
            }
            else -> PartyRoute.NotFound
        }
    }

    /** A guest's display name: no control characters or markup, collapsed spaces, at most [MAX_NAME] characters. */
    fun cleanName(raw: String?): String {
        val kept = StringBuilder()
        for (ch in raw.orEmpty()) {
            when {
                ch.isLetterOrDigit() || ch == ' ' || ch in "-_.'!?&" -> kept.append(ch)
                ch.isWhitespace() -> kept.append(' ')
            }
        }
        val name = kept.toString().replace(SPACES, " ").trim().take(MAX_NAME).trim()
        return name.ifEmpty { "Guest" }
    }

    /** A search query: control characters out, trimmed, at most [MAX_QUERY] characters. */
    fun cleanQuery(raw: String?): String =
        raw.orEmpty().filter { !it.isISOControl() }.trim().take(MAX_QUERY)

    /** Whether [id] looks like an opaque id this server could have issued. */
    fun isId(id: String?): Boolean = id != null && ID.matches(id)

    const val MAX_URI = 512
    const val MAX_NAME = 24
    const val MAX_QUERY = 80
    const val MAX_BODY = 1024
}
