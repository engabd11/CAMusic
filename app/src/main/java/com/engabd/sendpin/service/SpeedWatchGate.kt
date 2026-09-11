package com.engabd.sendpin.service

/**
 * The pure decision behind GPS speed tracking: when does [SpeedMonitor] hold a
 * location subscription at all. Split out for the same reason [SpeedAlert] was —
 * the real gate lives inside coroutines wired to `LocationManager` and DataStore,
 * and this is the one battery-critical policy worth testing on its own.
 *
 * The rule is strict, and deliberately so. GPS at a fix a second is one of the
 * most expensive things an app can ask a phone for, and every previous extra way
 * in — CAMusic holding a media session, *any other app* playing audio, a manual
 * tile tap — let the subscription live for hours in contexts (music at a desk, a
 * podcast on a train, a speaker in the kitchen) where there is no car and no
 * speed worth reading. What all of those have in common is that they say
 * "audio is playing", which is not evidence of a journey. Only the nominated car
 * being actually connected is, and the platform hands that signal over for free:
 * ACL broadcasts cost nothing to receive.
 */
object SpeedWatchGate {

    /**
     * True only when a speed feature is switched on **and** the phone is connected
     * to the designated car Bluetooth device.
     *
     * Both halves are required:
     *
     *  * **No feature on** — the alert and the adaptive volume are opt-in; someone
     *    who switched both off asked for no location subscription and gets none,
     *    even strapped into the car.
     *  * **No car link — no GPS, full stop.** Not "unless the tile was tapped":
     *    a manual override still shows the driving bar (that is about controls on
     *    screen, not about being in a car), but it no longer starts GPS. The
     *    designated-device link is the one signal this accepts.
     */
    fun shouldWatch(featureOn: Boolean, carLinked: Boolean): Boolean = featureOn && carLinked
}
