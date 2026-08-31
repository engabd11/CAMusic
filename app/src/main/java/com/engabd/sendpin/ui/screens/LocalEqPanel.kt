package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.audio.Biquad
import com.engabd.sendpin.audio.LocalDsp
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.systemNavInset
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
@Composable
fun ColumnScope.LocalEqBody(accent: Color) {
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context) }
    val scope = rememberCoroutineScope()
    val config by settings.localDsp.collectAsStateWithLifecycle(initialValue = LocalDsp.Config())

    fun update(next: LocalDsp.Config) {
        scope.launch { settings.setLocalDsp(next) }
    }

    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ToggleRow(
            title = "Equaliser",
            subtitle = "Ten bands, applied to music this phone plays itself",
            checked = config.enabled,
            accent = accent,
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
        config.bands.forEachIndexed { i, band ->
            BandRow(
                band = band,
                accent = accent,
                enabled = config.enabled,
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

        Spacer(Modifier.height(systemNavInset()))
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
        Slider(
            value = band.gainDb.coerceIn(-RANGE_DB, RANGE_DB),
            onValueChange = { onGain((Math.round(it * 2f) / 2f).coerceIn(-RANGE_DB, RANGE_DB)) },
            valueRange = -RANGE_DB..RANGE_DB,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent.a(0.7f),
                inactiveTrackColor = Hairline,
            ),
            modifier = Modifier.weight(1f),
        )
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

/** `125` under a kilohertz, `4k` over it — `16000` is four characters of nothing. */
private fun hzLabel(hz: Float): String =
    if (hz < 1_000f) "${hz.toInt()}" else "${(hz / 1_000f).let {
        if (it == it.toInt().toFloat()) it.toInt().toString() else "%.1f".format(it)
    }}k"
