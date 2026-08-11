package com.engabd.sendpin.crash

import android.app.Application
import android.os.Build
import android.util.Log
import com.engabd.sendpin.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Self-hosted crash reporter: catches uncaught exceptions, writes them to a
 * rotating local file, and can share them to GitHub either by opening the new-issue
 * URL or by posting via a personal access token.
 *
 * No third-party crash service is involved. Reports stay on the device until the
 * user chooses to send them, and automatic uploading is opt-in (a GitHub token must
 * be provided in settings).
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val MAX_STORED = 20
    private const val STORE_FILE = "crash_reports.json"

    private lateinit var app: Application
    private val ioScope = CoroutineScope(Dispatchers.IO)

    /** True after install() has been called. */
    val installed get() = ::app.isInitialized

    /**
     * Install the global exception handler. Must be called once, early in app startup
     * (SendpinApp.onCreate).
     */
    fun install(application: Application) {
        app = application
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                record(throwable, thread.name)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record crash", e)
            }
            // Always chain to the previous handler so the process still terminates
            // as Android expects; we are observers, not a replacement.
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun storeFile(): File = File(app.filesDir, STORE_FILE)

    /** Record a crash immediately. Safe to call from any thread. */
    fun record(throwable: Throwable, threadName: String = Thread.currentThread().name) {
        ioScope.launch { appendReport(makeReport(throwable, threadName)) }
    }

    private fun makeReport(throwable: Throwable, threadName: String): JSONObject {
        return JSONObject().apply {
            put("time", isoNow())
            put("exception_class", throwable.javaClass.name)
            put("message", throwable.message ?: JSONObject.NULL)
            put("stack_trace", Log.getStackTraceString(throwable))
            put("thread", threadName)
            put("version_code", BuildConfig.VERSION_CODE)
            put("version_name", BuildConfig.VERSION_NAME)
            put("api_level", Build.VERSION.SDK_INT)
            put("device", Build.MANUFACTURER + " " + Build.MODEL)
            put("reported", false)
        }
    }

    private fun readReports(): JSONArray = try {
        val file = storeFile()
        if (file.exists()) JSONArray(file.readText()) else JSONArray()
    } catch (e: Exception) {
        Log.w(TAG, "Could not read crash store", e)
        JSONArray()
    }

    private fun writeReports(array: JSONArray) {
        storeFile().writeText(array.toString())
    }

    private suspend fun appendReport(report: JSONObject) = withContext(Dispatchers.IO) {
        val array = readReports()
        array.put(report)
        while (array.length() > MAX_STORED) {
            array.remove(0)
        }
        writeReports(array)
    }

    /** All recorded crashes, most recent last. */
    fun reports(): List<CrashReport> = readReports().toReportList()

    /** The most recent unreported crash, if any. */
    fun lastUnreported(): CrashReport? = reports().lastOrNull { !it.reported }

    /** Mark the most recent crash as reported so it stops appearing as "new". */
    fun markLastReported() {
        ioScope.launch {
            val array = readReports()
            for (i in array.length() - 1 downTo 0) {
                if (!array.optJSONObject(i).optBoolean("reported", false)) {
                    array.getJSONObject(i).put("reported", true)
                    writeReports(array)
                    return@launch
                }
            }
        }
    }

    /** Delete all stored crash reports. */
    fun clear() {
        ioScope.launch { storeFile().delete() }
    }

    /**
     * Build a GitHub new-issue URL with the crash pre-filled. This requires no token
     * and no network permission — it simply opens the browser for the user to submit.
     */
    fun githubIssueUrl(repo: String, report: CrashReport): String {
        val title = percentEncode(report.title())
        val body = percentEncode(formatBody(report))
        return "https://github.com/" + repo + "/issues/new?title=" + title + "&body=" + body
    }

    /**
     * Post a crash directly to GitHub Issues using a personal access token.
     * Requires the repo string in "owner/repo" form.
     */
    suspend fun postToGitHub(
        repo: String,
        token: String,
        report: CrashReport,
        labels: List<String> = listOf("crash", "auto-report"),
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("title", report.title())
                put("body", formatBody(report))
                put("labels", JSONArray(labels))
            }.toString()

            val url = URL("https://api.github.com/repos/" + repo + "/issues")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "token " + token)
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 30000
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val code = conn.responseCode
            if (code in 200..299) {
                val number = JSONObject(response).optInt("number", -1)
                Result.success(if (number > 0) number.toString() else "submitted")
            } else {
                Result.failure(Exception("GitHub returned " + code + ": " + response))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun percentEncode(s: String): String {
        val out = StringBuilder()
        for (ch in s) {
            when {
                ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~' -> out.append(ch)
                ch == ' ' -> out.append('+')
                ch == '\n' -> out.append("%0A")
                ch == '"' -> out.append("%22")
                else -> out.append(String.format("%%%02X", ch.code))
            }
        }
        return out.toString()
    }

    private fun formatBody(r: CrashReport): String {
        val sb = StringBuilder()
        sb.append("### Crash report").append("\n")
        sb.append("- **Time:** ").append(r.time).append("\n")
        sb.append("- **Exception:** ").append(r.exceptionClass).append("\n")
        r.message?.let { sb.append("- **Message:** ").append(it).append("\n") }
        sb.append("- **Thread:** ").append(r.thread).append("\n")
        sb.append("- **Version:** ").append(r.versionName).append(" (").append(r.versionCode).append(")").append("\n")
        sb.append("- **API level:** ").append(r.apiLevel).append("\n")
        sb.append("- **Device:** ").append(r.device).append("\n")
        sb.append("\n")
        sb.append("```").append("\n")
        sb.append(r.stackTrace).append("\n")
        sb.append("```").append("\n")
        return sb.toString()
    }

    private fun JSONArray.toReportList(): List<CrashReport> {
        val list = ArrayList<CrashReport>(length())
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            list.add(
                CrashReport(
                    time = o.optString("time", ""),
                    exceptionClass = o.optString("exception_class", "Unknown"),
                    message = o.optString("message", "").takeIf { it != "null" },
                    stackTrace = o.optString("stack_trace", ""),
                    thread = o.optString("thread", ""),
                    versionCode = o.optInt("version_code", 0),
                    versionName = o.optString("version_name", ""),
                    apiLevel = o.optInt("api_level", 0),
                    device = o.optString("device", ""),
                    reported = o.optBoolean("reported", false),
                )
            )
        }
        return list
    }

    private fun isoNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
