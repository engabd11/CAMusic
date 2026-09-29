package com.engabd.sendpin.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.ui.theme.TextSecondary
import com.engabd.sendpin.usb.UsbDacProbe
import kotlinx.coroutines.launch

/**
 * What a connected USB DAC says it can do, read from the device itself.
 *
 * The first step of CAMusic's own USB audio driver (docs/plan/usb-bitperfect-driver.md):
 * the formats, rates, clocking and volume control a driver has to work with. Reading it
 * changes nothing and leaves Android playing through the DAC. Shared as text, it is also
 * how a DAC nobody here owns becomes a test case.
 */
@Composable
internal fun UsbDacReportCard(accent: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }

    SettingsCard(
        title = "USB DAC",
        lead = "What a connected USB DAC says it can play, read from the DAC itself.",
        info = "Android asks once for permission to use the USB device. Reading it " +
            "changes nothing: Android keeps playing through the DAC.\n\nThe report lists " +
            "the DAC's USB Audio Class version, every format and sample rate it offers, how " +
            "it keeps time, and whether it has its own volume control, followed by its raw " +
            "descriptors. This is groundwork for bit-perfect output, which needs CAMusic " +
            "to drive the DAC itself. If your DAC misbehaves, share this report on a GitHub " +
            "issue.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OledButton(
                text = if (reading) "Reading…" else "Read USB DAC",
                accent = accent,
                enabled = !reading,
                modifier = Modifier.weight(1f),
            ) {
                scope.launch {
                    reading = true
                    report = runCatching { UsbDacProbe.report(context) }
                        .getOrElse { "Could not read the DAC: ${it.message}" }
                    reading = false
                }
            }
            val text = report
            OledButton(
                text = "Share",
                accent = accent,
                outline = true,
                enabled = text != null && !reading,
                modifier = Modifier.weight(1f),
            ) { if (text != null) share(context, text) }
        }
        report?.let { text ->
            CardDivider()
            SelectionContainer {
                Text(
                    text,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OledButton(text = "Copy", accent = accent, outline = true) {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("USB DAC report", text))
            }
        }
    }
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share USB DAC report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
