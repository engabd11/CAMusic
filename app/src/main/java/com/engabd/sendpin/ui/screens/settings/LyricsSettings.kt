package com.engabd.sendpin.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.lyrics.AiLyricsTranslator
import com.engabd.sendpin.lyrics.LyricTranslationStore
import com.engabd.sendpin.lyrics.LyricsOffsets
import com.engabd.sendpin.lyrics.LyricsTranslator
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.theme.Ink2
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Lyrics page under Audio Engine & DSP. These switches used to sit at the bottom of
 * Playback behaviour, which they had outgrown: where lyrics come from, how they are drawn,
 * the timing nudge, and a translator with its own AI settings are a page's worth.
 *
 * One composable per card, like the other settings pages, so the host list composes them
 * lazily.
 */
@Composable
internal fun LyricsDisplayCard(settings: AppSettings, scope: CoroutineScope) {
    val wordByWord by settings.lyricsWordByWord.collectAsStateWithLifecycle(initialValue = true)
    SettingsCard(
        title = "Display",
        lead = "How lyrics are drawn on Now Playing.",
    ) {
        ToggleRow(
            title = "Word-by-word highlight",
            subtitle = "Fill each word in as it is sung",
            checked = wordByWord,
            accent = LocalAccent.current,
            info = "Uses the word timing in the lyrics when they have it (enhanced LRC, TTML, or " +
                "a server that sends it). Otherwise the words are spread across each line by " +
                "length, and drawn a little softer so you can tell it is an estimate.\n\n" +
                "With Reduce motion on, each word lights up whole instead of wiping across.",
        ) { on -> scope.launch { settings.setLyricsWordByWord(on) } }
    }
}

@Composable
internal fun LyricsOnlineCard(settings: AppSettings, scope: CoroutineScope) {
    val online by settings.lyricsOnline.collectAsStateWithLifecycle(initialValue = false)
    SettingsCard(
        title = "Online lyrics",
        lead = "Where lyrics come from when your library and the file have none.",
    ) {
        ToggleRow(
            title = "Find lyrics online",
            subtitle = "Ask LRCLIB when your library and the file have none. Sends the artist and title",
            checked = online,
            accent = LocalAccent.current,
        ) { on -> scope.launch { settings.setLyricsOnline(on) } }
        Note("Downloads keep their lyrics beside the file, so they work offline.")
    }
}

@Composable
internal fun LyricsTimingCard(settings: AppSettings, scope: CoroutineScope) {
    val offset by settings.lyricsOffsetMs.collectAsStateWithLifecycle(initialValue = 0)
    SettingsCard(
        title = "Timing",
        lead = "Nudging synced lyrics against the vocal, for every song.",
        info = "Providers stamp the same track differently, so there is no right answer here, " +
            "only what looks in time to you. Adjustments snap to 50 ms, which is finer than " +
            "anyone can pick out against a sung line.\n\nThis offset applies to every track. " +
            "For one song that is off on its own, use the timing buttons under its lyrics on " +
            "Now Playing; that is remembered for the song.\n\nTip: set it against a slow, clear " +
            "vocal rather than a fast one. If lyrics run late on some songs and early on " +
            "others, that is the provider disagreeing with itself and no single offset will " +
            "fix both.",
    ) {
        SliderRow(
            value = (offset + AppSettings.MAX_LYRICS_OFFSET_MS) /
                (2f * AppSettings.MAX_LYRICS_OFFSET_MS),
            format = { if (offset == 0) "0 ms" else "%+d ms".format(offset) },
            onChange = { f ->
                val ms = (f * 2f * AppSettings.MAX_LYRICS_OFFSET_MS -
                    AppSettings.MAX_LYRICS_OFFSET_MS).toInt()
                scope.launch { settings.setLyricsOffsetMs((ms / 50) * 50) }
            },
        )
        Note("Later ← → earlier")
    }
}

@Composable
internal fun LyricsTranslationCard(settings: AppSettings, scope: CoroutineScope) {
    val translate by settings.lyricsTranslate.collectAsStateWithLifecycle(initialValue = false)
    val targetLang by settings.lyricsTranslateLang.collectAsStateWithLifecycle(initialValue = "")
    val translateWifiOnly by settings.lyricsTranslateWifiOnly.collectAsStateWithLifecycle(initialValue = true)
    val translator by settings.lyricsTranslator.collectAsStateWithLifecycle(initialValue = "mlkit")
    val about by settings.lyricsAbout.collectAsStateWithLifecycle(initialValue = true)
    val context = LocalContext.current
    val mlKitAvailable = remember { LyricsTranslator.isAvailable(context) }
    val useAi = translator == "ai"
    // Your own AI needs neither ML Kit nor Play services, so it keeps the switch usable.
    val translateAvailable = mlKitAvailable || useAi
    SettingsCard(
        title = "Translation",
        lead = "Show a translation under each line, on this phone or with an AI you choose.",
    ) {
        ToggleRow(
            title = "Translate lyrics",
            subtitle = when {
                translateAvailable -> "Show a translation under each line"
                else -> "Choose your own AI below to translate here"
            },
            checked = translate && translateAvailable,
            accent = LocalAccent.current,
            info = "Pick who translates. On this phone uses Google's on-device ML Kit: each " +
                "language is downloaded once, about 30 MB, and lyrics stay on the phone. " +
                "While it is on, ML Kit also sends Google usage statistics about the " +
                "translator and may fetch updates for it.\n\nYour own AI sends the whole song " +
                "to an AI you choose, which translates for meaning instead of line by line. " +
                "Finished translations are kept for next time.",
        ) { on -> scope.launch { settings.setLyricsTranslate(on) } }
        if (translate || !mlKitAvailable) {
            FieldLabel("Translator")
            DropdownPicker(
                options = listOf("On this phone (Google ML Kit)", "Your own AI"),
                selectedIndex = if (useAi) 1 else 0,
                accent = LocalAccent.current,
            ) { i -> scope.launch { settings.setLyricsTranslator(if (i == 1) "ai" else "mlkit") } }
        }
        if (!mlKitAvailable && !useAi) {
            Note(LyricsTranslator.UNAVAILABLE_COPY)
        } else if (translate) {
            val languages = remember { LyricsTranslator.languages().ifEmpty { AiLyricsTranslator.COMMON_LANGUAGES } }
            val phoneLanguage = remember {
                java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.getDefault())
                    .replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) }
            }
            val options = listOf("Same as this phone ($phoneLanguage)") + languages.map { it.second }
            FieldLabel("Translate into")
            DropdownPicker(
                options = options,
                selectedIndex = languages.indexOfFirst { it.first == targetLang }.let { if (it < 0) 0 else it + 1 },
                accent = LocalAccent.current,
            ) { i -> scope.launch { settings.setLyricsTranslateLang(if (i == 0) "" else languages[i - 1].first) } }
            if (useAi) {
                LyricsAiFields(settings, scope, about)
            } else {
                ToggleRow(
                    title = "Download languages on Wi-Fi only",
                    checked = translateWifiOnly,
                    accent = LocalAccent.current,
                ) { on -> scope.launch { settings.setLyricsTranslateWifiOnly(on) } }
                Note(
                    "Once a language is on the phone, translating happens here. ML Kit's own " +
                        "usage statistics go to Google.",
                )
            }
        }
    }
}

/**
 * The "Your own AI" half of the translation card: address, model, key, a Test button, and the
 * switch for the "About this song" note. Edits are held here until Save or Test, so a
 * half-typed address never fires a translation of the song that is playing.
 */
@Composable
private fun LyricsAiFields(settings: AppSettings, scope: CoroutineScope, about: Boolean) {
    val accent = LocalAccent.current
    val savedUrl by settings.lyricsAiUrl.collectAsStateWithLifecycle(initialValue = "")
    val savedModel by settings.lyricsAiModel.collectAsStateWithLifecycle(initialValue = "")
    val savedKey by settings.lyricsAiKey.collectAsStateWithLifecycle(initialValue = "")
    var url by remember(savedUrl) { mutableStateOf(savedUrl) }
    var model by remember(savedModel) { mutableStateOf(savedModel) }
    var key by remember(savedKey) { mutableStateOf(savedKey) }
    var showKey by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<String, Health>?>(null) }
    val dirty = url.trim() != savedUrl || model.trim() != savedModel || key.trim() != savedKey

    Note(
        "Works with any OpenAI-style address: Ollama or LM Studio on your own server, a free " +
            "Google Gemini key, or OpenRouter. Pick one to fill it in.",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AiLyricsTranslator.PRESETS.forEach { (label, presetUrl, presetModel) ->
            com.engabd.sendpin.ui.design.ToggleChip(label, selected = url.trim() == presetUrl) {
                url = presetUrl
                model = presetModel
                status = null
            }
        }
    }
    OledField(url, { url = it }, "Address", "http://192.168.1.10:11434/v1", accent)
    OledField(model, { model = it }, "Model name", "e.g. gemma3:12b", accent)
    SecretField(
        value = key, onChange = { key = it }, label = "API key (optional)", accent = accent,
        visible = showKey, onVisibilityChange = { showKey = it },
    )
    Note(AiLyricsTranslator.privacyNote(url))
    status?.let { (text, health) -> StatusLine(text, health, accent) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OledButton("Save", accent = accent, enabled = dirty) {
            scope.launch { settings.setLyricsAi(url, model, key); status = "Saved" to Health.GOOD }
        }
        OledButton("Test", accent = accent, outline = true, enabled = url.isNotBlank() && model.isNotBlank()) {
            status = "Asking ${model.trim()}..." to Health.WORKING
            scope.launch {
                settings.setLyricsAi(url, model, key)
                val r = AiLyricsTranslator().test(AiLyricsTranslator.Config(url, model.trim(), key))
                status = r.message to if (r.ok) Health.GOOD else Health.BAD
            }
        }
    }
    ToggleRow(
        title = "Explain what the song is about",
        subtitle = "A short note above the lyrics, collapsed until you tap it",
        checked = about,
        accent = accent,
    ) { on -> scope.launch { settings.setLyricsAbout(on) } }
}

/**
 * What the app keeps for each song, with a way to start fresh: finished translations
 * (they live in the app's own data, so only this button removes them) and per-song timing
 * nudges. A translation on screen right now stays until the song changes, and a nudge on
 * the playing song is forgotten from the next track on.
 */
@Composable
internal fun LyricsSavedCard() {
    val context = LocalContext.current
    val accent = LocalAccent.current
    var refresh by remember { mutableIntStateOf(0) }
    var translations by remember { mutableStateOf<LyricTranslationStore.Summary?>(null) }
    var nudged by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val ioScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            translations = LyricTranslationStore.summary(context)
            nudged = LyricsOffsets.count(context)
        }
    }
    val saved = translations
    SettingsCard(
        title = "Saved for each song",
        lead = "Translations and timing nudges are kept on this phone. Clear them here.",
    ) {
        Note(
            when {
                saved == null -> "Counting saved translations..."
                saved.count == 0 -> "No saved translations yet."
                else -> "${songs(saved.count)} translated · ${size(saved.bytes)}"
            },
        )
        OledButton("Clear saved translations", accent = accent, outline = true, enabled = (saved?.count ?: 0) > 0) {
            confirm = "translations"
        }
        Note(
            when (nudged) {
                0 -> "No song has its own timing nudge."
                else -> "${songs(nudged)} with its own timing nudge"
            },
        )
        OledButton("Reset lyric timing for all songs", accent = accent, outline = true, enabled = nudged > 0) {
            confirm = "timing"
        }
    }
    val which = confirm
    if (which != null) {
        val translations = which == "translations"
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Ink2,
            title = {
                Text(
                    if (translations) "Clear saved translations?" else "Reset lyric timing?",
                    color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp,
                )
            },
            text = {
                Text(
                    if (translations) {
                        "This removes the translations saved for ${songs(saved?.count ?: 0)}. " +
                            "A song is translated again the next time you play it with translation on."
                    } else {
                        "This clears the Earlier and Later nudge on ${songs(nudged)}. " +
                            "The timing slider above, which moves every song together, stays as it is."
                    },
                    color = TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    // Disk work off the main thread; the counts refresh once it is done.
                    ioScope.launch(Dispatchers.IO) {
                        if (translations) LyricTranslationStore.clear(context) else LyricsOffsets.clearAll(context)
                        withContext(Dispatchers.Main) { refresh++ }
                    }
                }) { Text(if (translations) "Clear" else "Reset", color = accent, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("Cancel", color = TextMuted) }
            },
        )
    }
}

private fun songs(n: Int) = if (n == 1) "1 song" else "$n songs"

private fun size(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
