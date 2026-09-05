package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.audio.Biquad
import com.engabd.sendpin.audio.LocalDsp
import com.engabd.sendpin.audio.OutputMode
import com.engabd.sendpin.audio.SignalPath
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ui.design.HSlider
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.screens.settings.ToggleRow
import com.engabd.sendpin.ui.theme.*
import kotlinx.coroutines.launch

/** The dB either way each band slider covers. Twelve is where a boost starts to hurt. */
private const val RANGE_DB = 12f

/**
 * The equaliser for audio this phone decodes.
 *
 * A ten-band graphic EQ rather than the parametric editor the Music Assistant
 * panel offers, and that is a deliberate difference rather than a shortfall: MA's
 * DSP is a server-side pipeline someone sets up once for a room, while this is the
 * thing you reach for on a train because the headphones are thin. Ten sliders and a
 * preset is the right shape for that; frequency and Q per band is not.
 *
 * Writes straight to settings on every change. The processor picks the new curve up
 * at the top of the next buffer, so a slider moves the sound under the finger.
 */
/**
 * [modifier] carries whatever the *caller* needs the body to be, and that is the whole
 * reason this is a parameter.
 *
 * It used to open `Column(Modifier.weight(1f).verticalScroll(...))` — a `ColumnScope`
 * extension that assumed it was inside something with a bounded height. The Now Playing
 * sheet is (`fillMaxHeight(0.78f)`), so there it worked. The settings page is a
 * `LazyColumn`, which measures its items with `maxHeight = Infinity`, and Compose's
 * Column policy only honours `weight` against a *bounded* maximum — otherwise the
 * weighted child is measured at zero. So the settings card drew its title and its lead
 * sentence and laid the ten sliders, the toggles and the presets out at no height at
 * all: an equaliser page that looked deliberately empty for as long as it existed.
 *
 * The body is therefore intrinsically sized and pads nothing, and the two callers say
 * what they need: the sheet wraps it in the scroll and the nav-bar inset it always
 * wanted, and the settings card simply lets it size itself inside the padding a
 * [com.engabd.sendpin.ui.screens.settings.SettingsCard] already applies.
 */
@Composable
fun LocalEqBody(accent: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context) }
    val scope = rememberCoroutineScope()
    val config by settings.localDsp.collectAsStateWithLifecycle(initialValue = LocalDsp.Config())
    // Some output modes build the player with no processors at all — see
    // TapRenderersFactory and ExclusiveOutput — so this curve has nothing to run
    // on while one is selected. Kept visible rather than hidden: it still edits the
    // stored config, which takes over again the moment the mode steps back.
    //
    // Read from [SignalPath], not from `settings.exclusiveOutput`. The setting names
    // one of the three things that drop the chain; the observed state names all of
    // them, and the one it was missing is the common one — High-resolution output
    // bypasses the processors too, but only when float actually engages, which depends
    // on what the decoder handed back and cannot be known from a preference. So the
    // panel used to sit there looking live on a hi-res track while nothing it did
    // reached the audio.
    val signal by SignalPath.state.collectAsStateWithLifecycle()
    val bypassed = signal.processorsBypassed

    fun update(next: LocalDsp.Config) {
        scope.launch { settings.setLocalDsp(next) }
    }

    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (bypassed) {
            Text(
                "Output mode is set to ${OutputMode.PURE.title} or deeper, so nothing of " +
                    "this app's — including this curve — is running on what's playing. Step " +
                    "back to ${OutputMode.STANDARD.title} under Settings > Playback & audio > " +
                    "Output to use the equaliser again.",
                color = WarnAmber,
                fontFamily = AppFont,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        ToggleRow(
            title = "Equaliser",
            subtitle = if (bypassed)
                "Inert on ${OutputMode.PURE.title} output — see the note above"
            else
                "Ten bands, applied to music this phone plays itself",
            checked = config.enabled,
            accent = accent,
            enabled = !bypassed,
            info = "Covers this library and your downloads - everything this phone " +
                "decodes.\n\nIt is not applied to anything Music Assistant streams to a " +
                "speaker: that has its own DSP running on the server, and doing it here " +
                "as well would be two equalisers in series.\n\nA change takes effect on " +
                "the next fraction of a second of audio rather than the next track, so " +
                "you can hear a slider as you move it.\n\nTip: cutting sounds better " +
                "than boosting. If a mix is short of bass, try pulling the mids down " +
                "before pushing the bass up - there is no headroom cost and no risk of " +
                "clipping.",
        ) { update(config.copy(enabled = it)) }

        // The sliders. Vertical would look more like a rack, and would give each
        // band about eighteen pixels of travel on a phone — so they are horizontal
        // rows, which is also what makes the frequency and the gain readable.
        // Still drawn while Exclusive output is on - see the note at the top of
        // this column - just disabled, same as the toggle above.
        config.bands.forEachIndexed { i, band ->
            BandRow(
                band = band,
                accent = accent,
                enabled = config.enabled && !bypassed,
                onGain = { db ->
                    update(config.copy(bands = config.bands.toMutableList().also {
                        it[i] = band.copy(gainDb = db)
                    }))
                },
            )
        }

        val preamp = config.effectivePreampDb()
        ToggleRow(
            title = "Automatic headroom",
            subtitle = when {
                !config.autoPreamp -> "Off. A boosted curve on a loud track will clip."
                preamp < 0f -> "Pulling back ${"%.1f".format(-preamp)} dB so the boosts do not clip"
                else -> "Nothing to pull back - no band is boosted"
            },
            checked = config.autoPreamp,
            accent = accent,
            enabled = !bypassed,
            info = "A boosted band has to come from somewhere. Digital audio has a hard " +
                "ceiling, and material mastered anywhere near it is already at that " +
                "ceiling - so lifting a band pushes it through, which is heard as " +
                "distortion and usually blamed on the file.\n\nThis pulls the whole " +
                "signal down by roughly what the boosts add, so the shape of the curve " +
                "survives and the peaks do not.\n\nTip: leave it on. Turning it off is " +
                "for someone who has measured their own headroom and wants the level " +
                "back.",
        ) { update(config.copy(autoPreamp = it)) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EqPreset("Flat", accent) { update(config.copy(bands = LocalDsp.Config.defaultBands())) }
            EqPreset("Bass", accent) { update(config.copy(bands = curve(listOf(6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)))) }
            EqPreset("Vocal", accent) { update(config.copy(bands = curve(listOf(-2f, -2f, 0f, 1f, 3f, 4f, 3f, 1f, 0f, 0f)))) }
            EqPreset("Bright", accent) { update(config.copy(bands = curve(listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 5f)))) }
        }
    }
}

/** A named curve as bands, on the default centres. */
private fun curve(gains: List<Float>): List<LocalDsp.Band> =
    LocalDsp.Config.defaultBands().mapIndexed { i, band ->
        band.copy(gainDb = gains.getOrElse(i) { 0f })
    }

@Composable
private fun BandRow(
    band: LocalDsp.Band,
    accent: Color,
    enabled: Boolean,
    onGain: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            hzLabel(band.frequency),
            color = if (enabled) TextSecondary else TextFaint,
            fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp,
            modifier = Modifier.width(44.dp),
        )
        // The house slider, so an equaliser band looks like every other setting in the
        // app and picks up the magnifier — which matters more here than anywhere else,
        // because a band's whole value is the number and the thumb covers it.
        //
        // [HSlider] has no disabled state of its own, so the row swallows pointer
        // events on the Initial pass instead of letting the knob follow a finger whose
        // movement is going to be discarded.
        Box(
            Modifier
                .weight(1f)
                .then(if (enabled) Modifier else Modifier.swallowPointerInput())
                .alpha(if (enabled) 1f else 0.45f),
        ) {
            HSlider(
                value = gainToSlider(band.gainDb),
                onChange = { onGain(sliderToGain(it)) },
                accented = enabled,
                label = { gainLabel(sliderToGain(it)) },
            )
        }
        Text(
            if (Biquad.isFlat(band.gainDb)) "0" else "%+.1f".format(band.gainDb),
            color = when {
                !enabled -> TextFaint
                Biquad.isFlat(band.gainDb) -> TextFaint
                else -> accent
            },
            fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp),
        )
    }
}

@Composable
private fun EqPreset(label: String, accent: Color, onClick: () -> Unit) {
    Text(
        label,
        color = TextSecondary, fontFamily = AppFont,
        fontWeight = FontWeight.Bold, fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(100))
            .background(Glass)
            .border(1.dp, Hairline, RoundedCornerShape(100))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    )
}

/**
 * Gain to slider fraction and back, snapped to the half-decibel the sliders have always
 * moved in. [HSlider] works in 0..1, where the old M3 slider took a `valueRange`.
 */
private fun gainToSlider(db: Float): Float =
    ((db.coerceIn(-RANGE_DB, RANGE_DB) + RANGE_DB) / (2f * RANGE_DB)).coerceIn(0f, 1f)

private fun sliderToGain(fraction: Float): Float {
    val db = fraction.coerceIn(0f, 1f) * 2f * RANGE_DB - RANGE_DB
    return (Math.round(db * 2f) / 2f).coerceIn(-RANGE_DB, RANGE_DB)
}

private fun gainLabel(db: Float): String = if (Biquad.isFlat(db)) "0 dB" else "%+.1f dB".format(db)

/**
 * Take every pointer event before the children see it.
 *
 * A disabled control that still tracks a finger and then discards the movement reads as
 * broken rather than disabled, and the Initial pass is the only place to stop that —
 * a parent's ordinary pointer input runs after its children have already handled it.
 */
private fun Modifier.swallowPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/** `125` under a kilohertz, `4k` over it — `16000` is four characters of nothing. */
private fun hzLabel(hz: Float): String =
    if (hz < 1_000f) "${hz.toInt()}" else "${(hz / 1_000f).let {
        if (it == it.toInt().toFloat()) it.toInt().toString() else "%.1f".format(it)
    }}k"
