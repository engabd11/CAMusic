package com.engabd.sendpin.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.IntentCompat
import com.engabd.sendpin.BuildConfig
import com.engabd.sendpin.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Updating the app from its own About page.
 *
 * CAMusic ships only as APKs on GitHub releases, so staying current meant opening the
 * releases page, picking the right file and sideloading it. This does those steps in
 * place: ask GitHub for the latest release, download the APK for this build's flavour,
 * check it, and hand it to Android's installer, which shows its own confirmation.
 * Checking is the user's tap, plus one quiet version-number lookup a day that only
 * raises a notification ([UpdateCheckWorker]) and can be switched off. It never
 * downloads or installs anything by itself.
 *
 * A singleton with its own scope, not a ViewModel: a 70 MB download should outlive
 * the user leaving the page, and coming back should show where it got to.
 */
object AppUpdater {

    private const val TAG = "AppUpdater"
    private const val LATEST_URL = "https://api.github.com/repos/engabd11/CAMusic/releases/latest"

    /** The package every release is built as. Anything else is a test copy. */
    private const val RELEASE_PACKAGE = "com.engabd.sendpin"

    private const val APK_DIR = "updates"
    private const val APK_NAME = "camusic-update.apk"

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val latest: String) : State
        data class Available(val release: ReleaseInfo) : State
        /** [fraction] is null while the size is unknown. */
        data class Downloading(val release: ReleaseInfo, val fraction: Float?) : State
        /** Downloaded and checked; waiting on "Install unknown apps" for CAMusic. */
        data class NeedsPermission(val release: ReleaseInfo) : State
        /**
         * Handed to Android; its own dialog is up, or about to be. [confirm] is that
         * dialog, kept so the page can open it again: Android may not let the app
         * open it from the background (the download finished while the user was
         * elsewhere), and it can be dismissed without an answer.
         */
        data class Installing(val release: ReleaseInfo, val confirm: Intent? = null) : State
        data class Failed(val message: String, val release: ReleaseInfo? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    /**
     * Why this install cannot update itself, or null when it can. Checked before any
     * download, so a test copy is told so instead of fetching 70 MB to be refused.
     */
    fun unsupportedReason(context: Context): String? = when {
        context.packageName != RELEASE_PACKAGE ->
            "This is a side-by-side test copy (${context.packageName}). It cannot update itself; install releases over the main app."
        else -> null
    }

    fun check(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            _state.value = State.Checking
            _state.value = try {
                val release = fetchLatest()
                when {
                    release == null -> State.Failed("The latest release has no ${BuildConfig.FLAVOR} APK.")
                    ReleaseParsing.isNewer(release.version, BuildConfig.VERSION_NAME) ->
                        State.Available(release)
                    else -> State.UpToDate(release.version)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                State.Failed("Could not reach GitHub: ${e.message ?: e.javaClass.simpleName}")
            }
            // A finished download from an earlier session is not worth keeping once
            // the answer is known: it is either this version or a stale one.
            if (_state.value !is State.Available) apkFile(app).delete()
        }
    }

    /** Download [release], check it, and install it — asking for the permission first if needed. */
    fun downloadAndInstall(context: Context, release: ReleaseInfo) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        unsupportedReason(app)?.let { _state.value = State.Failed(it, release); return }
        job = scope.launch {
            try {
                _state.value = State.Downloading(release, if (release.apkSize > 0) 0f else null)
                val file = download(app, release)
                verify(app, file)?.let { reason ->
                    file.delete()
                    _state.value = State.Failed(reason, release)
                    return@launch
                }
                proceedToInstall(app, release, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update download failed", e)
                apkFile(app).delete()
                _state.value = State.Failed("Download failed: ${e.message ?: e.javaClass.simpleName}", release)
            }
        }
    }

    /**
     * Back from the "Install unknown apps" page. Installs if it was granted; stays put
     * if not, so the button is still there to try again.
     */
    fun resumeAfterPermission(context: Context) {
        val s = _state.value as? State.NeedsPermission ?: return
        val app = context.applicationContext
        val file = apkFile(app)
        if (!file.exists()) {
            _state.value = State.Available(s.release)
            return
        }
        if (app.packageManager.canRequestPackageInstalls()) proceedToInstall(app, s.release, file)
    }

    /** The system page where CAMusic is allowed to install apps. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * The background check found [release]: show it on the About page when it is next
     * opened. Only over a state that has nothing in flight, so a download under way, or
     * an install the user is part way through, is never replaced by a fresh offer.
     */
    internal fun offer(release: ReleaseInfo) {
        val s = _state.value
        if (s is State.Idle || s is State.UpToDate || s is State.Available || (s is State.Failed && s.release == null)) {
            _state.value = State.Available(release)
        }
    }

    /** Back to the start, from a result the user has read. */
    fun reset() {
        if (job?.isActive == true) return
        _state.value = State.Idle
    }

    // ── Steps ─────────────────────────────────────────────────────────────

    /** Also the background check's one network call; see [UpdateCheckWorker]. */
    internal suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            // Never a cached answer: "is there an update" asked twice in a minute
            // should be asked of GitHub twice.
            .header("Cache-Control", "no-cache")
            .build()
        Http.base.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> throw IllegalStateException("no published release")
                response.code == 403 || response.code == 429 ->
                    throw IllegalStateException("GitHub is rate-limiting this network; try again in an hour")
                !response.isSuccessful -> throw IllegalStateException("GitHub answered ${response.code}")
            }
            ReleaseParsing.parseLatest(response.body?.string().orEmpty(), BuildConfig.FLAVOR)
        }
    }

    private fun apkFile(context: Context) = File(File(context.cacheDir, APK_DIR), APK_NAME)

    private suspend fun download(context: Context, release: ReleaseInfo): File = withContext(Dispatchers.IO) {
        val file = apkFile(context)
        file.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        val request = Request.Builder().url(release.apkUrl).build()
        Http.transfer().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("GitHub answered ${response.code}")
            val body = response.body ?: throw IllegalStateException("empty response")
            val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
            var read = 0L
            var lastShown = -1
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        if (total > 0) {
                            // Per percent, not per buffer: a thousand state emissions
                            // for one progress bar is recomposition for nothing.
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastShown) {
                                lastShown = pct
                                _state.value = State.Downloading(release, (read.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256 != null && actual != release.sha256) {
            file.delete()
            throw IllegalStateException("the file did not match the release's checksum")
        }
        file
    }

    /**
     * Null when [file] is an update Android will accept for this app, else why not.
     *
     * Android would refuse a mismatched APK itself, but only after its own dialog and
     * with a message about "package conflicts" that tells nobody anything.
     */
    private fun verify(context: Context, file: File): String? {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "The downloaded file is not an Android app."
        if (archive.packageName != context.packageName) {
            return "The download is for ${archive.packageName}, not this app."
        }
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (archive.longVersionCode <= installed.longVersionCode) {
            return "The download is not newer than what is installed."
        }
        // The new APK's signing history (its signer plus every key it rotated from)
        // must include the key this install is signed with, or Android will refuse it.
        val history = archive.signingInfo?.let {
            if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
        }.orEmpty().toSet()
        val current = installed.signingInfo?.apkContentsSigners.orEmpty()
        if (current.isEmpty() || !current.all { it in history }) {
            return "This copy of CAMusic is signed with a different key from the release " +
                "(a build from source, most likely), so Android will not update it in place. " +
                "Uninstall it and install the release from GitHub."
        }
        return null
    }

    private fun proceedToInstall(context: Context, release: ReleaseInfo, file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedsPermission(release)
            return
        }
        _state.value = State.Installing(release)
        try {
            install(context, file)
        } catch (e: Exception) {
            Log.w(TAG, "Install session failed", e)
            _state.value = State.Failed("Android would not start the install: ${e.message}", release)
        }
    }

    private fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            // Mutable because the installer fills in the status extras. Explicit, so
            // nothing else can receive it.
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, UpdateInstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    /** Android wants the user to confirm; [confirm] is its dialog. */
    internal fun onConfirmationNeeded(context: Context, confirm: Intent) {
        val release = (state.value as? State.Installing)?.release
        if (release != null) _state.value = State.Installing(release, confirm)
        try {
            context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // Blocked as a background start; the page offers the same intent.
            Log.w(TAG, "Could not open the installer dialog", e)
        }
    }

    /** Where [UpdateInstallReceiver] reports what Android did with the session. */
    internal fun onInstallStatus(context: Context, status: Int, message: String?) {
        val release = (state.value as? State.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                // Normally never seen: installing over ourselves ends this process.
                apkFile(context).delete()
                _state.value = State.Idle
            }
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                // The user said no in Android's dialog. Not an error — offer it again.
                _state.value = release?.let { State.Available(it) } ?: State.Idle
            else -> {
                apkFile(context).delete()
                _state.value = State.Failed("Android did not install the update: ${message ?: "status $status"}", release)
            }
        }
    }
}

/**
 * The install session's answer. Android asks for confirmation through here
 * ([PackageInstaller.STATUS_PENDING_USER_ACTION] carries the dialog to show), then
 * reports the outcome.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
            AppUpdater.onConfirmationNeeded(context, confirm)
            return
        }
        AppUpdater.onInstallStatus(context, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
