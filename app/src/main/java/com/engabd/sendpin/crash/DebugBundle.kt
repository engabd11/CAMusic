package com.engabd.sendpin.crash

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.core.content.FileProvider
import com.engabd.sendpin.BuildConfig
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One plain-text file with everything worth knowing when something has gone wrong.
 *
 * This replaces crash reporting *to* anywhere. The app used to be able to open a
 * GitHub issue on its own, given a personal access token — which meant a secret
 * stored on the phone, a settings page asking for it, and a report that carried a
 * stack trace and nothing else. What actually gets a bug fixed is the context
 * around it: the log lines from the minutes before, what was playing and from
 * where, which settings were on. So the app now produces one file that holds all
 * of that, and the user attaches it to an issue themselves. Nothing leaves the
 * phone unless they send it, and there is no token to leak.
 *
 * Sections, in the order someone reading it wants them: the build and the phone,
 * memory and network, what is playing, the analysis queue, every stored crash, a
 * settings snapshot with secrets left out, and the app's own logcat.
 */
object DebugBundle {

    private const val SHARED_DIR = "shared"

    /** The most recent lines of the app's log that are kept. Enough to cover a session. */
    private const val LOG_LINES = 6_000

    /** Preference names carrying secrets are dropped from the settings section outright. */
    private val SECRET_KEY_HINTS = listOf("password", "token", "secret", "key", "servers", "credential")

    /** The name the file is written and shared under. */
    fun fileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        return "camusic-debug-$stamp.txt"
    }

    /**
     * Build the bundle into the app's shareable cache directory and return it.
     *
     * Everything here is best-effort: a section that cannot be gathered says so in
     * the file rather than stopping the file being made. A debug bundle that fails
     * to build because one of its inputs is broken would be broken exactly when it
     * is needed.
     */
    suspend fun write(context: Context): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
        // Only the newest bundle is kept: they are a few hundred kilobytes each and
        // the share sheet reads the one it was handed.
        dir.listFiles { f -> f.name.startsWith("camusic-debug-") }?.forEach { it.delete() }
        val file = File(dir, fileName())
        file.writeText(build(context))
        file
    }

    /** A share-sheet intent for [file], for attaching it wherever the user chooses. */
    fun shareIntent(context: Context, file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.shared", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share debug file")
    }

    /** The file's text, section by section. */
    suspend fun build(context: Context): String = buildString {
        section("CAMusic debug bundle") {
            line("Written", isoNow())
            line("App", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.FLAVOR} ${BuildConfig.BUILD_TYPE}")
            line("Package", context.packageName)
            line("Device", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            line("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}")
            line("ABI", Build.SUPPORTED_ABIS.joinToString(","))
            line("Process uptime", "${SystemClock.elapsedRealtime() / 1000} s since boot; pid ${Process.myPid()}")
        }
        section("Memory") {
            val rt = Runtime.getRuntime()
            line("Heap used", "${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB")
            line("Heap max", "${rt.maxMemory() / 1_048_576} MB")
            line("Native heap", "${android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576} MB")
        }
        section("Network") {
            attempt {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
                if (caps == null) {
                    line("Active network", "none")
                } else {
                    val transports = buildList {
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
                    }
                    line("Active network", transports.ifEmpty { listOf("other") }.joinToString("+"))
                    line("Metered", (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).toString())
                    line("Validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED).toString())
                }
            }
        }

        val app = context.applicationContext as? SendpinApp
        val settings = AppSettings(context)

        section("Library") {
            attempt {
                val active = settings.activeServer.firstOrNullSafe()
                line("Active server", active?.let { "${it.kind.name} \"${it.displayName}\" at ${it.host}" } ?: "none")
                line("Servers configured", settings.servers.firstOrNullSafe()?.size?.toString() ?: "?")
                line("Music source", app?.musicSource?.value?.providerId ?: "none")
            }
        }
        section("Playback") {
            attempt {
                val player = app?.localPlayer
                val track = player?.current?.value
                line("Playing", player?.playing?.value?.toString() ?: "?")
                line("Track", track?.let { "\"${it.title}\" — ${it.artist ?: "?"} (${it.durationMs / 1000} s)" } ?: "none")
                line("Source", when {
                    track == null -> "—"
                    track.localPath != null -> "downloaded file"
                    track.streamUrl != null -> "stream from ${Uri.parse(track.streamUrl).host ?: "?"}"
                    else -> "?"
                })
                line("Source quality", track?.sourceQuality?.toString() ?: "?")
                line("Queue", "${player?.queue?.value?.size ?: 0} tracks")
            }
        }
        section("Track analysis") {
            attempt {
                val progress = app?.trackScans?.progress?.value
                if (progress == null) {
                    line("State", "not started")
                } else {
                    line("Analysing", progress.current?.let { "$it (${progress.currentFraction?.let { f -> "${(f * 100).toInt()}%" } ?: "…"})" } ?: "idle")
                    line("Queued", progress.pending.toString())
                    line("Sweep", if (progress.sweeping) "${progress.sweepDone} of ${progress.sweepTotal}" else "not running")
                    line("Parked for Wi-Fi", progress.parked.toString())
                    line("Failed this session", progress.failed.toString())
                    progress.failures.forEach { f ->
                        line("  ${f.title}", f.why + if (f.retryable) "" else " (final)")
                    }
                }
            }
        }
        section("Crashes recorded (${CrashReporter.reports().size})") {
            val reports = CrashReporter.reports()
            if (reports.isEmpty()) appendLine("none")
            reports.asReversed().forEach { r ->
                appendLine("--- ${r.time} · ${r.versionName} (${r.versionCode}) · API ${r.apiLevel} · ${r.device}")
                appendLine("${r.exceptionClass}: ${r.message ?: ""} [thread ${r.thread}]")
                appendLine(r.stackTrace.trimEnd())
                appendLine()
            }
        }
        section("Settings (secrets omitted)") {
            attempt {
                settings.diagnosticSnapshot()
                    .filterKeys { key -> SECRET_KEY_HINTS.none { hint -> key.contains(hint, ignoreCase = true) } }
                    .toSortedMap()
                    .forEach { (k, v) -> line(k, v) }
            }
        }
        section("Log (this process, last $LOG_LINES lines)") {
            appendLine(readLogcat())
        }
    }

    /**
     * The app's own log, as `logcat` will give it to an app without READ_LOGS: every
     * line this process wrote. This is where the MA, player, Hue and scanner tags
     * land, and it is the single most useful thing in the file.
     */
    private fun readLogcat(): String = try {
        val proc = ProcessBuilder(
            "logcat", "-d", "-v", "threadtime", "-t", LOG_LINES.toString(), "--pid=${Process.myPid()}",
        ).redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        text.ifBlank { "(logcat returned nothing — the process may have just restarted)" }
    } catch (e: Exception) {
        "(could not read logcat: ${e.message})"
    }

    private fun isoNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    // ── Formatting ────────────────────────────────────────────────────────

    private inline fun StringBuilder.section(title: String, body: StringBuilder.() -> Unit) {
        appendLine("==== $title ====")
        body()
        appendLine()
    }

    private fun StringBuilder.line(key: String, value: String) {
        append(key).append(": ").appendLine(value)
    }

    /** Run [body], and if it throws, write the failure into the file where its section would be. */
    private inline fun StringBuilder.attempt(body: StringBuilder.() -> Unit) {
        try {
            body()
        } catch (e: Exception) {
            appendLine("(unavailable: ${e.javaClass.simpleName}: ${e.message})")
        }
    }

    private suspend fun <T> Flow<T>.firstOrNullSafe(): T? =
        try { first() } catch (_: Exception) { null }
}
