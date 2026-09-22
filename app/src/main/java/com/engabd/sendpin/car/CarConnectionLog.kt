package com.engabd.sendpin.car

import android.content.Context

/**
 * When a car last actually connected, and who it was.
 *
 * This exists because of how the Android Auto surface fails. Everything about it is
 * invisible from the phone: the browse tree is drawn by another process on another
 * screen, and when the app does not appear in the car at all there is nothing on
 * either device that distinguishes "Android Auto never asked us" from "it asked and
 * we answered badly". The first is a pairing or trust problem the driver can fix; the
 * second is a bug in this app. Guessing between them cost a whole investigation.
 *
 * So the service writes a line here every time a controller connects, and Settings ›
 * Driving & Android Auto reads it back. A blank record after a trip is itself the
 * finding: the car never reached us.
 *
 * SharedPreferences rather than DataStore on purpose — this is written from
 * [CarLibrarySessionCallback.onConnect], which is not a coroutine and must not block
 * or dispatch. The same reasoning, and the same storage, as
 * [com.engabd.sendpin.library.LocalFavourites].
 */
object CarConnectionLog {

    private const val PREFS = "car_connections"
    private const val KEY_AT = "last_at"
    private const val KEY_PACKAGE = "last_package"

    /** One recorded connection, or null if nothing has ever connected. */
    data class Entry(val atMs: Long, val packageName: String)

    fun record(context: Context, packageName: String?) {
        val name = packageName?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_AT, System.currentTimeMillis())
                .putString(KEY_PACKAGE, name)
                .apply()
        }
    }

    fun last(context: Context): Entry? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_AT, 0L)
        val pkg = prefs.getString(KEY_PACKAGE, null)
        if (at <= 0L || pkg.isNullOrBlank()) null else Entry(at, pkg)
    }.getOrNull()

    /**
     * Whether an entry is Android Auto rather than something else on the phone.
     *
     * Worth distinguishing: this app's own UI, Assistant and the system's media
     * resumption all connect to the same session, so a non-blank record is not by
     * itself evidence that a *car* ever did.
     */
    fun isAndroidAuto(entry: Entry): Boolean =
        entry.packageName == ANDROID_AUTO_PACKAGE || entry.packageName == ANDROID_AUTO_PACKAGE_AAOS

    const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"

    /** Android Automotive's own media host, for a head unit running the APK. */
    const val ANDROID_AUTO_PACKAGE_AAOS = "com.android.car.media"
}
