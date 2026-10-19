package com.engabd.sendpin.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.engabd.sendpin.BuildConfig
import com.engabd.sendpin.MainActivity
import com.engabd.sendpin.R
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.service.OpenAppRequest
import com.engabd.sendpin.service.launchIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * The decision, and the run around it, kept free of Android so both are unit tested:
 * the worker below only supplies the real fetch, settings and notification.
 */
object UpdateCheck {

    /**
     * Whether to raise a notification for [latest].
     *
     * Only for a version later than the one running ([ReleaseParsing.isNewer], so a
     * pre-release label or an unreadable tag never counts), only once per version
     * ([lastNotified]), never when the user turned it off, and never for a side-by-side
     * test copy, which cannot update itself.
     */
    fun shouldNotify(
        enabled: Boolean,
        supported: Boolean,
        latest: String,
        current: String,
        lastNotified: String?,
    ): Boolean =
        enabled && supported &&
            ReleaseParsing.isNewer(latest, current) &&
            lastNotified?.let { !sameVersion(it, latest) } != false

    private fun sameVersion(a: String, b: String) =
        a.trim().removePrefix("v").removePrefix("V") == b.trim().removePrefix("v").removePrefix("V")

    /** What a run did, for the log and the tests. */
    enum class Outcome { SKIPPED, UP_TO_DATE, ALREADY_TOLD, NOTIFIED, NO_RELEASE, FAILED }

    /**
     * One background check. Never throws (except cancellation), and has no "retry"
     * result: a failure, a rate limit included, is simply tried again next period, so
     * a bad network can never turn into a stream of requests to GitHub.
     */
    suspend fun run(
        enabled: Boolean,
        supported: Boolean,
        current: String,
        lastNotified: suspend () -> String?,
        fetch: suspend () -> ReleaseInfo?,
        offer: (ReleaseInfo) -> Unit,
        notify: suspend (ReleaseInfo) -> Boolean,
        remember: suspend (String) -> Unit,
        onError: (Exception) -> Unit = {},
    ): Outcome {
        // Before any network: a switched-off or unsupported install asks no one anything.
        if (!enabled || !supported) return Outcome.SKIPPED
        val release = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
            return Outcome.FAILED
        } ?: return Outcome.NO_RELEASE
        if (!ReleaseParsing.isNewer(release.version, current)) return Outcome.UP_TO_DATE
        // The About page should show the offer whether or not we tell anyone about it.
        offer(release)
        if (!shouldNotify(enabled, supported, release.version, current, lastNotified())) return Outcome.ALREADY_TOLD
        // Remembered only when the notification really went out, so a missing
        // notification permission today does not swallow the announcement for ever.
        if (notify(release)) remember(release.version)
        return Outcome.NOTIFIED
    }

    internal const val TAG = "UpdateCheck"
}

/** The daily background lookup of the latest release version. It never downloads or installs. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = AppSettings(applicationContext)
        val outcome = UpdateCheck.run(
            enabled = settings.updateNotify.first(),
            supported = AppUpdater.unsupportedReason(applicationContext) == null,
            current = BuildConfig.VERSION_NAME,
            lastNotified = { settings.updateNotifiedVersion.first() },
            fetch = { AppUpdater.fetchLatest() },
            offer = { AppUpdater.offer(it) },
            notify = { UpdateNotifier.post(applicationContext, it) },
            remember = { settings.setUpdateNotifiedVersion(it) },
            onError = { Log.w(UpdateCheck.TAG, "Background update check failed: ${it.message}") },
        )
        Log.i(UpdateCheck.TAG, "Background update check: $outcome")
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "update_check"

        /** Schedule the daily check when [enabled], cancel it when not. Safe to call at every start. */
        fun sync(context: Context, enabled: Boolean) {
            val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // KEEP: the period keeps counting across app starts instead of resetting
            // (and so never firing) for someone who opens the app every day.
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** The "CAMusic x.y.z is ready" notification. Opens Settings, About; installing stays in the app. */
object UpdateNotifier {

    private const val CHANNEL_ID = "app_updates"
    private const val NOTIFICATION_ID = 0xA0D1

    /** Whether a notification was actually posted. False without the Android 13+ grant. */
    fun post(context: Context, release: ReleaseInfo): Boolean {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return runCatching {
            createChannel(app)
            val open = openUpdate(app)
            val notes = release.headline
            val builder = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_camusic)
                .setContentTitle("CAMusic ${release.version} is ready")
                .setContentText(notes.ifBlank { "Open Settings, About to update." })
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                // Installing needs the in-app flow and its permission prompts.
                .addAction(0, "Update", open)
            if (notes.isNotBlank()) builder.setStyle(NotificationCompat.BigTextStyle().bigText(notes))
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, builder.build())
        }.isSuccess
    }

    private fun openUpdate(app: Context): PendingIntent =
        PendingIntent.getActivity(
            app,
            OpenAppRequest.UPDATE,
            launchIntent(app).apply {
                action = MainActivity.ACTION_OPEN_UPDATE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createChannel(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when a new version of CAMusic is out"
            },
        )
    }
}
