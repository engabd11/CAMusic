package com.engabd.sendpin.party

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The HTTP skin over [PartyEngine].
 *
 * **Plain HTTP on purpose.** The party lives on the host's Wi-Fi and is reached by
 * IP address; there is no domain to hold a certificate for, and browsers will not
 * trust a self-signed one without a warning that would put guests off. What protects
 * it instead is that it listens on the Wi-Fi interface only, the link carries a
 * random 128-bit token, and nothing in a response is worth stealing: no addresses,
 * credentials, stream URLs or file paths ever leave the phone.
 *
 * Binds to [hostname] only (the Wi-Fi IPv4 address, never 0.0.0.0), serves nothing
 * but the three party files, sends no CORS headers, and answers a bounded number of
 * connections at once.
 */
class PartyServer(
    hostname: String,
    port: Int,
    private val engine: PartyEngine,
    private val asset: (String) -> ByteArray?,
) : NanoHTTPD(hostname, port) {

    init {
        setAsyncRunner(BoundedRunner(MAX_CONNECTIONS))
    }

    fun begin() = start(READ_TIMEOUT_MS, false)

    override fun serve(session: IHTTPSession): Response {
        val response = try {
            route(session)
        } catch (e: Exception) {
            plain(Response.Status.INTERNAL_ERROR, "Something went wrong")
        }
        return response.secured()
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri.orEmpty() + (session.queryParameterString?.let { "?$it" } ?: "")
        val route = PartyPaths.route(uri, engine.token)
        val get = session.method == Method.GET || session.method == Method.HEAD
        return when (route) {
            PartyRoute.NotFound -> plain(Response.Status.NOT_FOUND, "Not found")
            is PartyRoute.Asset -> {
                if (!get) return plain(Response.Status.METHOD_NOT_ALLOWED, "Not allowed")
                val bytes = asset(route.name) ?: return plain(Response.Status.NOT_FOUND, "Not found")
                bytesResponse(Response.Status.OK, PartyPaths.ASSETS.getValue(route.name), bytes)
            }
            is PartyRoute.Api -> api(session, route)
        }
    }

    private fun api(session: IHTTPSession, route: PartyRoute.Api): Response {
        val method = session.method
        if (method != Method.GET && method != Method.POST) return plain(Response.Status.METHOD_NOT_ALLOWED, "Not allowed")
        var body: JsonObject? = null
        if (method == Method.POST) {
            val declared = session.headers["content-length"]?.toLongOrNull() ?: 0L
            if (declared < 0 || declared > PartyPaths.MAX_BODY) return plain(statusOf(413), "Too big")
            val raw = ByteArray(declared.toInt())
            var read = 0
            while (read < raw.size) {
                val n = session.inputStream.read(raw, read, raw.size - read)
                if (n < 0) break
                read += n
            }
            body = runCatching { Json.parseToJsonElement(String(raw, 0, read, Charsets.UTF_8)) as? JsonObject }.getOrNull()
        }
        val query = session.parms.orEmpty()
        val result = runBlocking {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                engine.handle(
                    route, method.name, session.headers["x-guest-id"], session.remoteIpAddress.orEmpty(), query, body,
                )
            }
        } ?: PartyResponse.error(504, "That took too long")
        return bytesResponse(statusOf(result.status), result.contentType, result.body).also {
            // A cover never changes for an id, so the browser may keep it between polls.
            if (result.status == 200 && result.contentType.startsWith("image/")) it.addHeader("Cache-Control", "private, max-age=3600")
        }
    }

    /** Any status code, including the few the library's own enum leaves out. */
    private fun statusOf(code: Int): Response.IStatus = object : Response.IStatus {
        override fun getRequestStatus() = code
        // The status line is written from this text, so it has to carry the code.
        override fun getDescription() = "$code " + when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"; 410 -> "Gone"
            413 -> "Payload Too Large"; 429 -> "Too Many Requests"; 502 -> "Bad Gateway"
            504 -> "Gateway Timeout"; else -> "Error"
        }
    }

    private fun plain(status: Response.IStatus, text: String) =
        bytesResponse(status, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))

    private fun bytesResponse(status: Response.IStatus, type: String, bytes: ByteArray) =
        newFixedLengthResponse(status, type, ByteArrayInputStream(bytes), bytes.size.toLong())

    private fun Response.secured(): Response = apply {
        addHeader("Content-Security-Policy", "default-src 'self'; img-src 'self' data:")
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("Referrer-Policy", "no-referrer")
        if (getHeader("Cache-Control") == null) addHeader("Cache-Control", "no-store")
        addHeader("X-Frame-Options", "DENY")
        // No Access-Control-* header is ever sent: another site's script cannot read
        // a response, so a guest's browser tab cannot be used to reach the party.
    }

    /** At most [limit] connections served at once; the rest are refused rather than queued without bound. */
    private class BoundedRunner(limit: Int) : AsyncRunner {
        private val running = java.util.Collections.synchronizedSet(HashSet<ClientHandler>())
        private val pool = ThreadPoolExecutor(
            limit, limit, 30, TimeUnit.SECONDS, ArrayBlockingQueue(limit),
            { r -> Thread(r, "party-http").apply { isDaemon = true } },
        )

        override fun closeAll() {
            running.toList().forEach { it.close() }
            pool.shutdownNow()
        }

        override fun closed(clientHandler: ClientHandler) { running.remove(clientHandler) }

        override fun exec(clientHandler: ClientHandler) {
            running.add(clientHandler)
            try {
                pool.execute(clientHandler)
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                running.remove(clientHandler)
                clientHandler.close()
            }
        }
    }

    companion object {
        const val MAX_CONNECTIONS = 16
        const val READ_TIMEOUT_MS = 5_000
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}
