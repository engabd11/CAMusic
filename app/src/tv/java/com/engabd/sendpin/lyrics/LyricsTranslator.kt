package com.engabd.sendpin.lyrics

import android.content.Context

/**
 * The TV build's lyric translator: there is none.
 *
 * Google ML Kit's translator is about 45 MB of native code across processor types,
 * which would quadruple the TV APK for a feature a 10-foot screen does not offer.
 * The phone build's translator lives in the mobile source set; this keeps the same
 * shape so shared code compiles, and always answers that translation is unavailable.
 */
object LyricsTranslator {

    sealed interface Result {
        data class Done(val lines: List<String?>) : Result
        data object SameLanguage : Result
        data object NeedsWifi : Result
        data class Unavailable(val reason: String) : Result
        data class Failed(val reason: String) : Result
    }

    @Suppress("UNUSED_PARAMETER")
    fun isAvailable(context: Context): Boolean = false

    const val UNAVAILABLE_COPY =
        "Lyric translation is in the phone app. Everything else in lyrics works as normal."

    @Suppress("UNUSED_PARAMETER")
    fun targetCode(tag: String): String? = null

    fun languages(): List<Pair<String, String>> = emptyList()

    @Suppress("UNUSED_PARAMETER")
    suspend fun translate(
        context: Context,
        lines: List<String>,
        targetTag: String,
        wifiOnly: Boolean,
        cacheKey: String,
        onProgress: (LyricsTranslation.Working) -> Unit = {},
    ): Result = Result.Unavailable(UNAVAILABLE_COPY)
}
