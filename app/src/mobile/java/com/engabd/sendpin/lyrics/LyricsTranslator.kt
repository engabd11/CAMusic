package com.engabd.sendpin.lyrics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * On-device lyric translation with Google ML Kit.
 *
 * The one network call is the language model's download, the first time a pair is
 * needed (Wi-Fi only unless the listener says otherwise). Translating itself runs on
 * the phone, and the lyric text never leaves it. Finished translations are cached per
 * track, so a song is translated once.
 *
 * Everything that touches ML Kit or Play services is inside [runCatching] or a
 * `catch (Throwable)`: on a phone without Google Play services (GrapheneOS and
 * friends) the library can be missing its backing service, and the right outcome is
 * [Result.Unavailable] and a toggle that says why, never a crash.
 */
object LyricsTranslator {

    sealed interface Result {
        /** One entry per input line; null where a line has nothing to translate. */
        data class Done(val lines: List<String?>) : Result

        /** The song is already in the target language. */
        data object SameLanguage : Result

        /** The model is not on the phone yet and the connection is not Wi-Fi. */
        data object NeedsWifi : Result

        /** ML Kit cannot run on this device at all. */
        data class Unavailable(val reason: String) : Result

        data class Failed(val reason: String) : Result
    }

    private const val TAG = "LyricsTranslator"

    /** Hand partial results over every few lines, not every line, so the pane is not redrawn for each. */
    private const val PROGRESS_EVERY = 4

    private fun nameOf(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    private const val DOWNLOAD_TIMEOUT_MS = 180_000L
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(String.serializer().nullable)

    /**
     * Whether translation can run here: a 64-bit ARM phone with Google Play services.
     * Cheap; safe to call from the UI.
     *
     * The translator's native library is about 16 MB for each processor type and is
     * stored uncompressed, so the APK carries only the arm64 copy (see the packaging
     * block in app/build.gradle.kts). Anything else, 32-bit phones, emulators and
     * Chromebooks, gets a disabled toggle rather than a missing library.
     */
    fun isAvailable(context: Context): Boolean = runCatching {
        Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" &&
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context.applicationContext) ==
            ConnectionResult.SUCCESS
    }.getOrDefault(false)

    /** The explanation shown where the toggle is disabled. */
    const val UNAVAILABLE_COPY =
        "Translation needs a 64-bit phone with Google Play services, and it isn't available here. " +
            "Everything else in lyrics works as normal."

    /** ML Kit's code for [tag] ("" means the device language), or null if it has no model. */
    fun targetCode(tag: String): String? =
        TranslateLanguage.fromLanguageTag(tag.ifBlank { Locale.getDefault().toLanguageTag() })
            ?: TranslateLanguage.fromLanguageTag(Locale.getDefault().language)

    /** Every language ML Kit can translate into, as (code, name in the device language). */
    fun languages(): List<Pair<String, String>> = runCatching {
        TranslateLanguage.getAllLanguages().map { code ->
            code to Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
                .replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }.sortedBy { it.second }
    }.getOrDefault(emptyList())

    /**
     * Translate [lines] (blank entries stay blank) into [targetTag].
     *
     * [cacheKey] ties the result to a track; the lyric text and target are folded in
     * so edited lyrics or another language never read a stale file.
     */
    suspend fun translate(
        context: Context,
        lines: List<String>,
        targetTag: String,
        wifiOnly: Boolean,
        cacheKey: String,
        onProgress: (LyricsTranslation.Working) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        try {
            if (!isAvailable(context)) return@withContext Result.Unavailable(UNAVAILABLE_COPY)
            val target = targetCode(targetTag)
                ?: return@withContext Result.Failed("That language isn't one the translator knows.")

            val cache = cacheFile(context, cacheKey, target, lines)
            readCache(cache, lines.size)?.let { return@withContext Result.Done(it) }

            val sample = lines.filter { it.isNotBlank() }.joinToString("\n").take(1_000)
            if (sample.isBlank()) return@withContext Result.Done(lines.map { null })
            val detected = LanguageIdentification.getClient().use { it.identifyLanguage(sample).await() }
            val source = TranslateLanguage.fromLanguageTag(detected)
                ?: return@withContext Result.Failed("Couldn't tell which language this song is in.")
            if (source == target) return@withContext Result.SameLanguage

            val translator = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build(),
            )
            try {
                val ready = modelsReady(source, target)
                if (!ready && wifiOnly && !onUnmeteredNetwork(context)) {
                    return@withContext Result.NeedsWifi
                }
                // The first song in a language waits on its download (about 30 MB), which
                // is most of the wait; say so, or the pane just looks stuck.
                if (!ready) onProgress(LyricsTranslation.Working("Getting ${nameOf(source)} ready to translate (one time, about 30 MB)..."))
                val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
                withTimeout(DOWNLOAD_TIMEOUT_MS) { translator.downloadModelIfNeeded(conditions).await() }
                // Line by line, handing over what is done so far: the opening lines can
                // show while the rest of the song is still being translated.
                val total = lines.count { it.isNotBlank() }
                var done = 0
                val out = ArrayList<String?>(lines.size)
                for (line in lines) {
                    if (line.isBlank()) { out += null; continue }
                    out += translator.translate(line).await().takeIf { it.isNotBlank() }
                    done++
                    if (done % PROGRESS_EVERY == 0 || done == total) {
                        onProgress(LyricsTranslation.Working("Translating line $done of $total...", out + List(lines.size - out.size) { null }))
                    }
                }
                writeCache(cache, out)
                Result.Done(out)
            } finally {
                runCatching { translator.close() }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Result.Failed("The language download took too long. Try again on a steadier connection.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            // NoClassDefFoundError / ApiException from a phone with no usable service land here.
            // Logged in full: the listener's message is deliberately plain, so this line is
            // the only place the real cause shows up.
            Log.w(TAG, "Lyric translation failed", t)
            Result.Failed("Translation isn't working on this phone right now.")
        }
    }

    private suspend fun modelsReady(source: String, target: String): Boolean = runCatching {
        val manager = RemoteModelManager.getInstance()
        fun model(code: String) = TranslateRemoteModel.Builder(code).build()
        // English is bundled; every other language is a downloadable model.
        listOf(source, target).filter { it != TranslateLanguage.ENGLISH }
            .all { manager.isModelDownloaded(model(it)).await() }
    }.getOrDefault(false)

    private fun onUnmeteredNetwork(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    // ── Cache ─────────────────────────────────────────────────────────────

    private fun cacheFile(context: Context, key: String, target: String, lines: List<String>): File {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$key|$target|${lines.joinToString("\n").hashCode()}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(LyricTranslationStore.dir(context), "$digest.json")
    }

    private fun readCache(file: File, expected: Int): List<String?>? = runCatching {
        if (!file.isFile) return null
        json.decodeFromString(listSerializer, file.readText()).takeIf { it.size == expected }
            ?.also { LyricTranslationStore.touch(file) }
    }.getOrNull()

    private fun writeCache(file: File, lines: List<String?>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(listSerializer, lines))
            file.parentFile?.let { LyricTranslationStore.prune(it) }
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
