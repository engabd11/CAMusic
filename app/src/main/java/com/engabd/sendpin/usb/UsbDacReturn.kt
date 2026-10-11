package com.engabd.sendpin.usb

/**
 * Whether a DAC that dropped out mid-song has come back soon enough for the song to carry
 * on by itself. The timing half of [UsbDacWatcher], kept free of Android to be testable.
 *
 * Some DACs restart on their own while playing. A Sennheiser BTD 700 (a Bluetooth
 * transmitter) has been seen doing it on a Pixel: it drops off USB mid-song and is back
 * a second and a half later as a new device. To Android that is an unplug
 * and a plug-in, so the player pauses, as for unplugged headphones, and the new device
 * needs USB permission again. Only a DAC back within [returnWindowMs] counts as the
 * same dropout. One that comes back later was unplugged on purpose.
 *
 * The permission comes from Android's dialog, which can take a while to be answered, so
 * that answer has [permissionWindowMs] from the DAC's return.
 *
 * Times are `elapsedRealtime` milliseconds. Thread-safe: losses are reported from the
 * playback thread, everything else from the main thread.
 */
internal class UsbDacReturn(
    private val returnWindowMs: Long = 20_000,
    private val permissionWindowMs: Long = 60_000,
) {
    private data class Dropout(val key: String, val at: Long)

    /** The most recent DAC lost while CAMusic held it. */
    private var last: Dropout? = null

    /** A loss the player paused for, so it should carry on when that DAC is back. */
    private var awaiting: Dropout? = null
    private var backAt: Long? = null

    @Synchronized
    fun lost(key: String, now: Long) {
        last = Dropout(key, now)
    }

    /** The player paused for the last loss: carry on when that DAC comes back. */
    @Synchronized
    fun awaitReturn(now: Long) {
        val l = last ?: return
        if (now - l.at > returnWindowMs) return
        awaiting = l
        backAt = null
    }

    /** Someone else decided (play pressed, another track chosen): nothing to carry on. */
    @Synchronized
    fun cancel() {
        awaiting = null
        backAt = null
    }

    /** A DAC was plugged in. True when playback should carry on now. */
    @Synchronized
    fun attached(key: String, now: Long, permitted: Boolean): Boolean {
        val a = awaiting ?: return false
        if (a.key != key) return false
        if (now - a.at > returnWindowMs) {
            cancel()
            return false
        }
        backAt = now
        if (!permitted) return false
        cancel()
        return true
    }

    /** USB permission arrived for a DAC. True when playback should carry on now. */
    @Synchronized
    fun permitted(key: String, now: Long): Boolean {
        val a = awaiting ?: return false
        val back = backAt ?: return false
        if (a.key != key) return false
        cancel()
        return now - back <= permissionWindowMs
    }
}
