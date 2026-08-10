package com.engabd.sendpin.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.engabd.sendpin.MainActivity

/**
 * The "tap this notification to open the app" intent, shared by all three services.
 *
 * Deliberately **not** [androidx.core.app.TaskStackBuilder]. That helper stamps
 * `FLAG_ACTIVITY_CLEAR_TASK` onto the root intent — it exists to synthesise a back
 * stack for a deep link, which is the opposite of what a media notification wants.
 * Every tap therefore tore the running task down and built a fresh one: the Activity,
 * every ViewModel under it and the whole Compose tree were destroyed and rebuilt
 * *while a foreground media service was streaming*. Opening the app from the shade
 * mid-playback is exactly the case that made worst.
 *
 * `NEW_TASK or SINGLE_TOP` against the `singleTask` activity declared in the manifest
 * brings the existing task forward with its state intact, which is what tapping a
 * media notification is supposed to do.
 *
 * [requestCode] must be distinct per caller. [PendingIntent] matches on request code
 * and [Intent.filterEquals] — which ignores flags and extras — so three services
 * sharing one code would be three handles to whichever intent was registered last.
 */
fun openAppIntent(context: Context, requestCode: Int): PendingIntent =
    PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

/** Request codes for [openAppIntent] — one per service, never reused. */
object OpenAppRequest {
    const val CONNECTION = 100
    const val MEDIA = 101
    const val LOCAL = 102
    const val TILE = 103
}
