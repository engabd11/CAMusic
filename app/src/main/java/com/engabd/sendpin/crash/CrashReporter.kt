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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Catches uncaught exceptions and writes them to a rotating local file.
 *
 * That is all it does now. It used to be able to open a GitHub issue by itself,
 * given a personal access token typed into Settings — a secret on the phone for a
 * report that carried a stack trace and nothing around it. The crashes recorded
 * here are read back into [DebugBundle], which is the one thing the app produces
 * for a bug report, and nothing leaves the device unless the user shares that file.
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
