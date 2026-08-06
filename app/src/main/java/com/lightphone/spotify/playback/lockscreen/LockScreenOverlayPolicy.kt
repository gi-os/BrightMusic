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
 * ### Why "not in the foreground" stands in for "the lock screen is showing"
 *
 * LightOS is a single-activity app: its lock screen is a navigation destination inside the same
 * `MainActivity` as its launcher, so nothing outside the process can tell the two apart —
 * `UsageStatsManager` and `getRunningTasks` both see one activity in one package either way. What
 * *is* reliable is how the lock screen gets there: the SDK's `registerLockReceiver` brings that
 * activity to the front on every `ACTION_SCREEN_OFF` while `ForceFocusLevel.Always` is set, which is
 * the default. So the screen coming back on means the lock screen is what is in front of the user,
 * whatever app they were in beforehand.
 *
 * The home button is a drawn circle inside that activity rather than a hardware key, so its press
 * produces no key event and no broadcast either. It is caught as a touch *outside* the overlay window
 * (`FLAG_WATCH_OUTSIDE_TOUCH`), which is also what a swipe to unlock or a tap anywhere else on the
 * lock screen looks like — all of them mean the user is doing something other than controlling
 * playback, and all of them should take the row away.
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
