package com.engabd.sendpin.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.engabd.sendpin.lyrics.AiLyricsTranslator
import com.engabd.sendpin.lyrics.LyricsTranslator
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * How the player *behaves*, as distinct from how it looks — gestures, the local
 * auto-queue's ranking, and what the app remembers about a play. Split out of
 * Appearance, which used to carry these alongside the theme and accent picker
 * under the doc comment "how it looks, and how it behaves" — a mix that had
 * nothing to do with new-user legibility and everything to do with there being
 * nowhere else for a behavioral setting to go.
 *
 * One composable per card rather than one Column of all of them: the settings
 * screen emits each as its own list item, so the host list composes and recycles
 * cards lazily instead of measuring the whole page in a single frame.
 */

@Composable
internal fun BehaviorPlayerCard(settings: AppSettings, accent: androidx.compose.ui.graphics.Color, scope: CoroutineScope) {
    val showVisualizer by settings.showVisualizer.collectAsStateWithLifecycle(initialValue = false)
    SettingsCard(
        title = "On the player",
        lead = "Tap the cover on Now Playing any time to flip it to this. Off by default.",
    ) {
        ToggleRow(
            title = "Visualizer by default",
            subtitle = "Start Now Playing already showing it, instead of the cover",
            checked = showVisualizer,
            accent = accent,
            info = "The live visualizer reads the same analysis Light Sync already runs, " +
                "drawn full-size in the cover's own place. It's always one tap away — " +
                "this only decides what Now Playing opens on.",
        ) { scope.launch { settings.setShowVisualizer(it) } }
    }
}

@Composable
internal fun BehaviorHandsCard(settings: AppSettings, accent: androidx.compose.ui.graphics.Color, scope: CoroutineScope) {
    val swipeToSkip by settings.swipeToSkip.collectAsStateWithLifecycle(initialValue = false)
    val sensorGestures by settings.sensorGestures.collectAsStateWithLifecycle(initialValue = false)
    SettingsCard(
        title = "Hands off the screen",
        lead = "Ways to change track without looking at the phone. Both off by default.",
    ) {
        ToggleRow(
            title = "Swipe to skip",
            subtitle = "On Now Playing: swipe right for next, left for previous",
            checked = swipeToSkip,
            accent = accent,
            info = "The whole cover becomes the gesture, so a track can be changed with a " +
                "thumb and no aim at all — which is the point in a pocket, in a car cradle, " +
                "or with gloves on.\n\nOff by default because it takes the horizontal swipe " +
                "away from anything else that might want it, and because an accidental " +
                "brush skipping a track is worse than reaching for a button.\n\nTip: the " +
                "lyrics and queue panels are reached by tapping the cover rather than " +
                "swiping it, so the two do not collide.",
        ) { scope.launch { settings.setSwipeToSkip(it) } }
        ToggleRow(
            title = "Shake, flip and tap",
            subtitle = "Shake to skip, lie it face-down to pause, double-tap the body to play/pause",
            checked = sensorGestures,
            accent = accent,
            info = "Shake: a sharp jerk skips to the next track. Flip face-down (with the " +
                "proximity sensor covered, the way it would be lying screen-down on a " +
                "table): pauses. Double-tap the phone's body: play/pause.\n\nAll three read " +
                "the accelerometer and proximity sensor, and only while this is on — " +
                "nothing is sent anywhere.",
        ) { on ->
            scope.launch { settings.setSensorGestures(on) }
            if (on) SendpinApp.instance.playbackGestureMonitor.start()
            else SendpinApp.instance.playbackGestureMonitor.stop()
        }
    }
}

@Composable
internal fun BehaviorScansCard(
    settings: AppSettings,
    accent: androidx.compose.ui.graphics.Color,
    scope: CoroutineScope,
    advanced: Boolean,
) {
    val djMode by settings.djMode.collectAsStateWithLifecycle(initialValue = false)
    val listeningDna by settings.listeningDna.collectAsStateWithLifecycle(initialValue = false)
    SettingsCard(
        title = "What the track scans are for",
        lead = "Two uses for the key and tempo the offline scan already works out. Both need " +
            "tracks that have been scanned, and both are off by default.",
    ) {
        ToggleRow(
            title = "Harmonic DJ mode",
            subtitle = "Weighs compatible key and tempo into the local auto-queue's ranking",
            checked = djMode,
            accent = accent,
            info = "\"Keep the music going\" already ranks the next few tracks by genre and " +
                "artist on every library this phone plays itself. DJ mode adds a bonus " +
                "on top of that ranking — never instead of it — for tracks whose key sits " +
                "well against what's playing on the Camelot wheel (the same or relative key, " +
                "or one step around it) and whose tempo is close, or exactly half or double " +
                "time. It has nothing to weigh while the queue is carrying on through an " +
                "album in order, which is a running order rather than a ranking." +
                "\n\nApplies whether that library is reached online or from downloads " +
                "on this phone. Never Music Assistant, which tops up its own queue " +
                "server-side with no key or tempo data to weigh. An unscanned track simply " +
                "keeps its plain genre/artist ranking.",
        ) { scope.launch { settings.setDjMode(it) } }
        if (advanced) {
            ToggleRow(
                title = "Listening DNA",
                subtitle = "Log each play's key and tempo, for the Stats screen",
                checked = listeningDna,
                accent = accent,
                info = "Adds a dominant-keys and a BPM sweet-spot breakdown to Stats, from a " +
                    "snapshot of each played track's key and tempo kept alongside the usual " +
                    "listening history.\n\nNothing extra is stored until this is turned on, and " +
                    "turning it off again stops new snapshots without deleting the ones already " +
                    "logged.",
            ) { scope.launch { settings.setListeningDna(it) } }
        }
    }
}

@Composable
internal fun BehaviorLyricsCard(settings: AppSettings, scope: CoroutineScope) {
    val offset by settings.lyricsOffsetMs.collectAsStateWithLifecycle(initialValue = 0)
    val online by settings.lyricsOnline.collectAsStateWithLifecycle(initialValue = false)
    val wordByWord by settings.lyricsWordByWord.collectAsStateWithLifecycle(initialValue = true)
    val translate by settings.lyricsTranslate.collectAsStateWithLifecycle(initialValue = false)
    val targetLang by settings.lyricsTranslateLang.collectAsStateWithLifecycle(initialValue = "")
    val translateWifiOnly by settings.lyricsTranslateWifiOnly.collectAsStateWithLifecycle(initialValue = true)
    val translator by settings.lyricsTranslator.collectAsStateWithLifecycle(initialValue = "mlkit")
    val about by settings.lyricsAbout.collectAsStateWithLifecycle(initialValue = true)
    SettingsCard(
        title = "Lyrics",
        lead = "Where lyrics come from, and nudging synced ones against the vocal.",
        info = "Providers stamp the same track differently, so there is no right answer here, " +
            "only what looks in time to you. Adjustments snap to 50 ms, which is finer than " +
            "anyone can pick out against a sung line.\n\nThis offset applies to every track. " +
            "For one song that is off on its own, use the timing buttons under its lyrics on " +
            "Now Playing; that is remembered for the song.\n\nTip: set it against a slow, clear " +
            "vocal rather than a fast one. If lyrics run late on some songs and early on " +
            "others, that is the provider disagreeing with itself and no single offset will " +
            "fix both.",
    ) {
        ToggleRow(
            title = "Find lyrics online",
            subtitle = "Ask LRCLIB when your library and the file have none. Sends the artist and title",
            checked = online,
            accent = com.engabd.sendpin.ui.design.LocalAccent.current,
        ) { on -> scope.launch { settings.setLyricsOnline(on) } }
        Note("Downloads keep their lyrics beside the file, so they work offline.")
        CardDivider()
        ToggleRow(
            title = "Word-by-word highlight",
            subtitle = "Fill each word in as it is sung",
            checked = wordByWord,
            accent = com.engabd.sendpin.ui.design.LocalAccent.current,
            info = "Uses the word timing in the lyrics when they have it (enhanced LRC, TTML, or " +
                "a server that sends it). Otherwise the words are spread across each line by " +
                "length, and drawn a little softer so you can tell it is an estimate.\n\n" +
                "With Reduce motion on, each word lights up whole instead of wiping across.",
        ) { on -> scope.launch { settings.setLyricsWordByWord(on) } }
        CardDivider()
        val context = androidx.compose.ui.platform.LocalContext.current
        val mlKitAvailable = remember { LyricsTranslator.isAvailable(context) }
        val useAi = translator == "ai"
        // Your own AI needs neither ML Kit nor Play services, so it keeps the switch usable.
        val translateAvailable = mlKitAvailable || useAi
        ToggleRow(
            title = "Translate lyrics",
            subtitle = when {
                translateAvailable -> "Show a translation under each line"
                else -> "Choose your own AI below to translate here"
            },
            checked = translate && translateAvailable,
            accent = com.engabd.sendpin.ui.design.LocalAccent.current,
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
                accent = com.engabd.sendpin.ui.design.LocalAccent.current,
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
                accent = com.engabd.sendpin.ui.design.LocalAccent.current,
            ) { i -> scope.launch { settings.setLyricsTranslateLang(if (i == 0) "" else languages[i - 1].first) } }
            if (useAi) {
                LyricsAiCard(settings, scope, about)
            } else {
                ToggleRow(
                    title = "Download languages on Wi-Fi only",
                    checked = translateWifiOnly,
                    accent = com.engabd.sendpin.ui.design.LocalAccent.current,
                ) { on -> scope.launch { settings.setLyricsTranslateWifiOnly(on) } }
                Note(
                    "Once a language is on the phone, translating happens here. ML Kit's own " +
                        "usage statistics go to Google.",
                )
            }
        }
        CardDivider()
        FieldLabel("Timing, every song")
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

/**
 * The "Your own AI" half of the lyrics card: address, model, key, a Test button, and the
 * switch for the "About this song" note. Edits are held here until Save or Test, so a
 * half-typed address never fires a translation of the song that is playing.
 */
@Composable
private fun LyricsAiCard(settings: AppSettings, scope: CoroutineScope, about: Boolean) {
    val accent = com.engabd.sendpin.ui.design.LocalAccent.current
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
