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
 *
 * The cost of that strictness is that the watch now has three separate ways to be
 * off, and they used to be indistinguishable from the outside: a feature switched
 * off, no car ever nominated, and a nominated car that is not connected all
 * produced the same silence. [state] names which one it is, so the settings card
 * can say so — the difference between "not driving yet" and "this can never
 * fire" is the whole of the feature's support burden.
 */
object SpeedWatchGate {

    /** Why the watch is on, or why it is not. See [state]. */
    enum class State {
        /** A feature is on and the nominated car is connected: GPS runs. */
        WATCHING,

        /** Neither the alert nor adaptive volume is switched on. Nothing to watch for. */
        NO_FEATURE,

        /**
         * No car has been nominated in Driving settings.
         *
         * The dead end worth naming out loud: the car link is the *only* way in, so
         * a speed alert switched on without a car picked can never fire, however
         * fast the phone is moving. Nothing on screen used to say so.
         */
        NO_CAR,

        /** A car is nominated, it just is not connected right now — the normal idle. */
        CAR_AWAY,
    }

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

    /**
     * The same decision, but saying *why* — see [State].
     *
     * Ordered so the answer is the first thing the user would have to fix: a
     * feature that is off makes the car irrelevant, and a car that was never
     * picked makes its connection state irrelevant in turn.
     *
     * [carLinked] cannot be true without [carNominated] (the link is to the
     * nominated address), but the order above means a caller that gets that wrong
     * is told to pick a car rather than being quietly sent to watch.
     */
    fun state(featureOn: Boolean, carNominated: Boolean, carLinked: Boolean): State = when {
        !featureOn -> State.NO_FEATURE
        !carNominated -> State.NO_CAR
        !carLinked -> State.CAR_AWAY
        else -> State.WATCHING
    }
}
