package com.engabd.sendpin.lyrics

import com.engabd.sendpin.data.Http
import com.engabd.sendpin.data.LanOnlyCleartext
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Lyric translation by an AI the listener points the app at: any server that speaks the
 * OpenAI chat-completions dialect. Ollama and LM Studio on a home server, Google's
 * Gemini through its OpenAI-compatible address, OpenRouter.
 *
 * Why this exists next to ML Kit: a line-by-line machine translation keeps the words and
 * loses the song. A language model is handed the whole lyric at once, and asked for the
 * meaning, the mood and the idiom, so "it's raining cats and dogs" does not come out
 * as animals.
 *
 * No Android and no Google code in here (only [Http]'s client), so it runs in the TV
 * build too, and in plain JVM tests against a MockWebServer.
 *
 * Privacy is the address: the whole lyric goes to wherever it points. [privacyNote]
 * says which, in words, for the Settings page.
 */
class AiLyricsTranslator(
    client: OkHttpClient = Http.base,
    readTimeoutMs: Long = READ_TIMEOUT_MS,
) {

    /** Where to ask. [apiKey] is optional: a server on your own network usually has none. */
    data class Config(val baseUrl: String, val model: String, val apiKey: String = "") {
        val usable: Boolean get() = model.isNotBlank() && endpoint() != null
    }

    sealed interface Result {
        /** One entry per input line; null where the input line was blank. */
        data class Done(val lines: List<String?>, val about: String?) : Result

        /** The AI says the song is already in the target language. */
        data object SameLanguage : Result

        /** [reason] is plain words for the listener, and never carries the key. */
        data class Failed(val reason: String) : Result
    }

    /** What the Test button reports. */
    data class TestResult(val ok: Boolean, val message: String)

    // Long reads: a model on a home server can take a minute or two to write a whole song.
    private val http = client.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Translate [lines] into [targetName] (a language's English name, e.g. "Spanish").
     *
     * [cacheKey] ties the result to a track; the lyric text, target, model and address
     * are folded in so edited lyrics, another language or another model never read a
     * stale file. [onProgress] is called about once a second with the wait so far.
     */
    suspend fun translate(
        config: Config,
        lines: List<String>,
        targetName: String,
        cacheDir: File,
        cacheKey: String,
        onProgress: (String) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val cache = cacheFile(cacheDir, cacheKey, targetName, config, lines)
        readCache(cache, lines.size)?.let { return@withContext Result.Done(it.first, it.second) }

        // Blank lines are not sent: they are the gaps in the song, and a model told to keep
        // them tends to fill or drop them. They are put back by position afterwards.
        val sent = lines.filter { it.isNotBlank() }
        if (sent.isEmpty()) return@withContext Result.Done(lines.map { null }, null)

        val started = System.nanoTime()
        try {
            coroutineScope {
                val ticker = launch {
                    while (true) {
                        val s = (System.nanoTime() - started) / 1_000_000_000L
                        onProgress("Asking ${config.model} to translate... ${s}s")
                        delay(1_000)
                    }
                }
                try {
                    ask(config, sent, targetName, cache, lines)
                } finally {
                    ticker.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Result.Failed(explain(e, config))
        }
    }

    private suspend fun ask(
        config: Config,
        sent: List<String>,
        targetName: String,
        cache: File,
        lines: List<String>,
    ): Result {
        val url = config.endpoint() ?: return Result.Failed(BAD_ADDRESS)
        val body = chat(config, systemPrompt(targetName), userPrompt(sent), json = true)
        var reply = post(url, config, body)
        // Some servers refuse response_format outright. Ask again without it, once; the
        // prompt asks for JSON anyway and the parser copes with a fence round it.
        if (reply.code == 400) reply = post(url, config, chat(config, systemPrompt(targetName), userPrompt(sent), json = false))
        if (reply.code !in 200..299) return Result.Failed(explain(reply.code, reply.text))

        val content = contentOf(reply.text)
            ?: return Result.Failed("The AI answered, but not with a translation. Try a different model.")
        val parsed = parse(content) ?: return Result.Failed(
            "The AI answered, but not in a form the app can read. Try a different model.",
        )
        if (parsed.same) return Result.SameLanguage

        val got = parsed.lines
        val slack = maxOf(2, sent.size / 10)
        if (kotlin.math.abs(got.size - sent.size) > slack) {
            return Result.Failed(
                "The AI sent back ${got.size} lines for a song of ${sent.size}, so they would not line up. " +
                    "Try again, or pick a stronger model.",
            )
        }
        // Close enough: pad with nothing or drop the tail, and do not cache, so the next
        // open asks again instead of keeping a misaligned song for good.
        val exact = got.size == sent.size
        val fitted = List(sent.size) { got.getOrNull(it)?.takeIf { s -> s.isNotBlank() } }
        var next = 0
        val out = lines.map { if (it.isBlank()) null else fitted[next++] }
        val about = parsed.about
        if (exact) writeCache(cache, out, about)
        return Result.Done(out, about)
    }

    /** A tiny request to check the address, key and model, in words the listener can act on. */
    suspend fun test(config: Config): TestResult = withContext(Dispatchers.IO) {
        val url = config.endpoint() ?: return@withContext TestResult(false, BAD_ADDRESS)
        if (config.model.isBlank()) return@withContext TestResult(false, "Add a model name first.")
        try {
            val body = buildJsonObject {
                put("model", config.model)
                put("temperature", 0)
                put("max_tokens", 16)
                put(
                    "messages",
                    buildJsonArray {
                        add(msg("user", "Reply with the single word: ready"))
                    },
                )
            }.toString()
            val reply = post(url, config, body)
            if (reply.code !in 200..299) return@withContext TestResult(false, explain(reply.code, reply.text))
            if (contentOf(reply.text) == null) {
                TestResult(false, "The address answered, but not like an AI service. Check the address ends in /v1 or similar.")
            } else {
                TestResult(true, "Connected. ${config.model} answered.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            TestResult(false, explain(e, config))
        }
    }

    // ── Request ──────────────────────────────────────────────────────────

    private class Reply(val code: Int, val text: String)

    private suspend fun post(url: HttpUrl, config: Config, body: String): Reply {
        val request = Request.Builder().url(url)
            .post(body.toRequestBody(JSON_TYPE))
            .header("Accept", "application/json")
            .apply { if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey.trim()}") }
            .build()
        http.newCall(request).await().use { r -> return Reply(r.code, r.body?.string().orEmpty()) }
    }

    private fun chat(config: Config, system: String, user: String, json: Boolean): String = buildJsonObject {
        put("model", config.model)
        put("temperature", 0.3)
        if (json) put("response_format", buildJsonObject { put("type", "json_object") })
        put(
            "messages",
            buildJsonArray {
                add(msg("system", system))
                add(msg("user", user))
            },
        )
    }.toString()

    private fun msg(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    // ── Errors ───────────────────────────────────────────────────────────

    private fun explain(e: IOException, config: Config): String {
        val host = config.endpoint()?.host ?: "that address"
        return when {
            e.message?.startsWith("Refusing to send") == true ->
                "Plain http only works for servers on your own network. Use an https address for $host."
            e is SocketTimeoutException ->
                "$host took too long to answer. A big model on a slow machine can need longer; a smaller model is quicker."
            e is UnknownHostException -> "Couldn't find $host. Check the address for typos."
            e is ConnectException -> "Couldn't reach $host. Check the address, and that the server is running and on this network."
            else -> "Couldn't reach $host. Check the address and your connection."
        }
    }

    private fun explain(code: Int, body: String): String = when (code) {
        401, 403 -> "The AI service refused the key. Check the API key in Settings."
        404 -> "The address or model wasn't found. Check the model name, and that the address ends in /v1 or similar."
        429 -> "The AI service is busy or its free limit is used up for now. Try again later."
        in 500..599 -> "The AI service had a problem (code $code). Try again later."
        else -> "The AI service didn't accept the request (code $code)." + errorDetail(body)
    }

    /** The server's own words for a 4xx, short, for the listener who can act on them. */
    private fun errorDetail(body: String): String = runCatching {
        val root = Json.parseToJsonElement(body)
        val err = (root as? JsonObject)?.get("error") ?: (root as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.get("error") }
        val msg = when (err) {
            is JsonObject -> (err["message"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> err.contentOrNull
            else -> null
        }
        msg?.takeIf { it.isNotBlank() }?.let { " It said: ${it.take(160)}" }.orEmpty()
    }.getOrDefault("")

    // ── Cache ────────────────────────────────────────────────────────────

    internal fun cacheFile(dir: File, key: String, target: String, config: Config, lines: List<String>): File {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("ai|$key|$target|${config.model}|${config.endpoint()}|${lines.joinToString("\n").hashCode()}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(dir, "lyric-translations"), "$digest.json")
    }

    private fun readCache(file: File, expected: Int): Pair<List<String?>, String?>? = runCatching {
        if (!file.isFile) return null
        val o = Json.parseToJsonElement(file.readText()) as JsonObject
        val lines = (o["lines"] as JsonArray).map { (it as? JsonPrimitive)?.contentOrNull }
        (lines to (o["about"] as? JsonPrimitive)?.contentOrNull).takeIf { lines.size == expected }
    }.getOrNull()

    private fun writeCache(file: File, lines: List<String?>, about: String?) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                buildJsonObject {
                    put("lines", buildJsonArray { lines.forEach { add(it?.let(::JsonPrimitive) ?: JsonNull) } })
                    if (about != null) put("about", about)
                }.toString(),
            )
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) = cont.resume(response)
        })
    }

    companion object {
        const val CONNECT_TIMEOUT_S = 10L
        const val READ_TIMEOUT_MS = 120_000L
        private val JSON_TYPE = "application/json".toMediaType()
        private const val BAD_ADDRESS = "That address doesn't look right. Try something like http://192.168.1.10:11434/v1"

        /** Quick-fill presets for the Settings page: label, address, model (may be blank). */
        val PRESETS: List<Triple<String, String, String>> = listOf(
            Triple("Ollama", "http://192.168.1.10:11434/v1", "gemma3:12b"),
            Triple("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
            Triple("OpenRouter", "https://openrouter.ai/api/v1", ""),
        )

        /** Languages offered when ML Kit's list is not there (the TV build), as tag to name. */
        val COMMON_LANGUAGES: List<Pair<String, String>> = listOf(
            "ar" to "Arabic", "bn" to "Bengali", "zh" to "Chinese", "nl" to "Dutch", "en" to "English",
            "fr" to "French", "de" to "German", "el" to "Greek", "he" to "Hebrew", "hi" to "Hindi",
            "id" to "Indonesian", "it" to "Italian", "ja" to "Japanese", "ko" to "Korean",
            "pl" to "Polish", "pt" to "Portuguese", "ro" to "Romanian", "ru" to "Russian",
            "es" to "Spanish", "sv" to "Swedish", "th" to "Thai", "tr" to "Turkish",
            "uk" to "Ukrainian", "ur" to "Urdu", "vi" to "Vietnamese",
        )

        /**
         * Where the lyrics go, said plainly. A local address (a LAN IP or a .local name; the HTTP layer
         * refuses plain http to anywhere else) keeps the words at home; any other host
         * receives them.
         */
        fun privacyNote(baseUrl: String): String {
            val url = Config(baseUrl, "x").endpoint()
                ?: return "Lyrics are sent to the address you enter, so choose one you trust."
            return if (LanOnlyCleartext.isLocal(url.host)) {
                "Lyrics go to ${url.host} on your own network and stay at home."
            } else {
                "Lyrics are sent to ${url.host} to be translated. Check their privacy terms; free plans often keep prompts."
            }
        }

        /** The English name of the language for [tag] ("" is the device's), for the prompt. */
        fun targetName(tag: String): String =
            java.util.Locale.forLanguageTag(tag.ifBlank { java.util.Locale.getDefault().toLanguageTag() })
                .getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { "English" }

        internal fun systemPrompt(targetName: String): String =
            "You translate song lyrics into $targetName. Translate for meaning, mood and idiom, " +
                "not word for word, and keep it natural and singable. Explain nothing inside the lines. " +
                "The lyrics arrive as numbered lines. Return one output line for each input line, in the same order, " +
                "so line 1 of your output is the translation of input line 1. " +
                "Reply with only a JSON object: {\"lines\": [\"...\"], \"about\": \"...\"}. " +
                "\"lines\" must hold exactly as many strings as there are numbered input lines. " +
                "\"about\" is two or three sentences in $targetName on what the song is about, " +
                "including any idioms or cultural references worth knowing. " +
                "If the lyrics are already in $targetName, reply with {\"same\": true} instead."

        internal fun userPrompt(lines: List<String>): String =
            lines.mapIndexed { i, l -> "${i + 1}: ${l.trim()}" }.joinToString("\n")

        internal class Parsed(val lines: List<String>, val about: String?, val same: Boolean)

        /** The assistant text out of a chat-completions reply, or null if it isn't one. */
        internal fun contentOf(body: String): String? = runCatching {
            val root = Json.parseToJsonElement(body) as JsonObject
            val first = (root["choices"] as JsonArray).first() as JsonObject
            ((first["message"] as JsonObject)["content"] as JsonPrimitive).contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() }

        /**
         * The JSON object in a model's reply: fenced in ```json, with a sentence before or
         * after it, or bare. Null when nothing parses.
         */
        internal fun parse(content: String): Parsed? {
            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val obj = runCatching { Json.parseToJsonElement(content.substring(start, end + 1)) as? JsonObject }
                .getOrNull() ?: return null
            val about = (obj["about"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            if ((obj["same"] as? JsonPrimitive)?.booleanOrNull == true && obj["lines"] == null) {
                return Parsed(emptyList(), about, same = true)
            }
            val arr = obj["lines"] as? JsonArray ?: return null
            val lines = arr.map { el: JsonElement -> (el as? JsonPrimitive)?.contentOrNull.orEmpty().trim() }
            return Parsed(lines, about, same = false)
        }

        internal fun Config.endpoint(): HttpUrl? {
            val raw = baseUrl.trim().trimEnd('/')
            if (raw.isEmpty()) return null
            val withScheme = if (raw.contains("://")) raw else "http://$raw"
            return "$withScheme/chat/completions".toHttpUrlOrNull()
        }
    }
}
