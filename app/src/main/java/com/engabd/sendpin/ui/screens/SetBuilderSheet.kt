package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.audio.SetBuilder
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.theme.*

/**
 * The set builder: a shape, a length, and a Build button.
 *
 * Three controls because there are only three decisions. Everything that makes
 * this work - the tempo, the key, the per-section energy of every scanned track -
 * the app already knows and the listener should never have to think about.
 *
 * States its coverage up front. The builder can only order tracks it has scanned,
 * and a sheet that quietly built a worse set out of half a library would be the
 * kind of silent shortfall this app tries not to have.
 */
@Composable
fun SetBuilderDialog(
    scanned: Int,
    total: Int,
    onDismiss: () -> Unit,
    onBuild: (SetBuilder.Curve, Int) -> Unit,
) {
    val accent = LocalAccent.current
    var curve by remember { mutableStateOf(SetBuilder.Curve.ARC) }
    var minutes by remember { mutableIntStateOf(45) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink2,
        title = {
            Text(
                "Build a set", color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.ExtraBold, fontSize = 17.sp,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "Orders your scanned tracks into a set with a shape: energy where the " +
                        "curve wants it, and keys and tempos that mix where there is a choice.",
                    color = TextMuted, fontFamily = AppFont, fontSize = 12.sp, lineHeight = 17.sp,
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SetBuilder.Curve.entries.forEach { option ->
                        CurveRow(option, option == curve, accent) { curve = option }
                    }
                }

                Column {
                    Text(
                        "About $minutes minutes",
                        color = accent, fontFamily = MonoFont,
                        fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    )
                    Slider(
                        value = minutes.toFloat(),
                        onValueChange = { minutes = (Math.round(it / 5f) * 5).coerceIn(15, 180) },
                        valueRange = 15f..180f,
                    )
                }

                Text(
                    if (scanned >= total) "All $total tracks here have been scanned."
                    else "$scanned of $total tracks here have been scanned. Only those can be " +
                        "placed on the curve - run a sweep under Settings, Light Sync, Track " +
                        "analysis to use the rest.",
                    color = if (scanned >= total) TextFaint else WarnAmber,
                    fontFamily = AppFont, fontSize = 11.sp, lineHeight = 15.sp,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onBuild(curve, minutes) },
                enabled = scanned >= 2,
            ) {
                Text(
                    "Build",
                    color = if (scanned >= 2) accent else TextFaint,
                    fontFamily = AppFont, fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted, fontFamily = AppFont)
            }
        },
    )
}

@Composable
private fun CurveRow(
    curve: SetBuilder.Curve,
    selected: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.a(0.12f) else Glass)
            .border(
                1.dp,
                if (selected) accent.a(0.40f) else Hairline,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            curve.label,
            color = if (selected) accent else TextPrimary,
            fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        )
        Text(
            curve.blurb,
            color = TextMuted, fontFamily = AppFont, fontSize = 11.sp, lineHeight = 15.sp,
        )
    }
}
