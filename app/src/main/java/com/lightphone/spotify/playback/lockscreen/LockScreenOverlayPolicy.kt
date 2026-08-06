package com.lightphone.spotify.playback.lockscreen

/**
 * Whether the playback controls should be on screen right now.
 *
 * A pure function of six facts, because the overlay has six independent triggers — the screen turning
 * on and off, a tap outside the row, a long press on it, the app coming to the front, playback
 * starting and stopping, and the setting itself — and the previous generation of this kind of code in
 * this repo went wrong twice by spreading the rule across the places that observe those facts. Each
 * trigger writes one field of [Inputs] and asks again.
 *
 * ### Which app is in front, and why that has to be asked
 *
 * The first cut of this had no such input: it assumed that because LightOS force-focuses its own
 * activity on every `ACTION_SCREEN_OFF`, "the screen came on" meant "the lock screen is in front".
 * That is true of the lock screen and says nothing about the *rest* of the time, so the row also
 * appeared over other apps. [Inputs.onLightOs] is now a real answer from `UsageStatsManager` —
 * the top package compared against whichever package owns the HOME intent, which on this phone is
 * LightOS.
 *
 * It still cannot separate LightOS's lock screen from LightOS's launcher: LightOS is a single-activity
 * app and both are destinations inside the same `MainActivity`, so `UsageStatsManager` and
 * `getRunningTasks` see one activity in one package either way. The dismissal covers that. The home
 * button is a drawn circle rather than a hardware key, so its press emits no key event and no
 * broadcast — but it is a touch, and a touch *outside* the overlay window arrives as
 * `ACTION_OUTSIDE` (`FLAG_WATCH_OUTSIDE_TOUCH`). So does a swipe to unlock, and so does a tap
 * anywhere else on the lock screen: all of them mean the user is doing something other than
 * controlling playback, and all of them take the row away until the next time the screen wakes.
 */
object LockScreenOverlayPolicy {

    /** Everything the decision depends on. */
    data class Inputs(
        /** The user's setting. */
        val enabled: Boolean,
        /** `Settings.canDrawOverlays`, re-read rather than remembered — an appop can be revoked. */
        val canDrawOverlays: Boolean,
        /** A track or episode is loaded. Paused counts: that is when a play button is most useful. */
        val hasTrack: Boolean,
        val screenOn: Boolean,
        /**
         * LightOS itself is the app in front — its lock screen or its launcher. False for every other
         * app, which is the whole point: playback controls belong on the lock screen and nowhere else.
         */
        val onLightOs: Boolean,
        /** This app's own UI is in front, so the real player is a better control surface. */
        val appForeground: Boolean,
        /**
         * The user dismissed the row during this wake, by long-pressing it or by touching the lock
         * screen somewhere else. Cleared on the next screen-off, so the next wake starts fresh.
         */
        val dismissedThisWake: Boolean,
    )

    enum class Action { Show, Hide, Nothing }

    fun wanted(i: Inputs): Boolean =
        i.enabled &&
            i.canDrawOverlays &&
            i.hasTrack &&
            i.screenOn &&
            i.onLightOs &&
            !i.appForeground &&
            !i.dismissedThisWake

    /**
     * @param shown whether the window is currently attached, so the caller only ever adds or removes
     *   it once — `addView` twice throws, and `removeView` on a detached view logs a leak warning.
     */
    fun decide(i: Inputs, shown: Boolean): Action = when {
        wanted(i) && !shown -> Action.Show
        !wanted(i) && shown -> Action.Hide
        else -> Action.Nothing
    }
}
