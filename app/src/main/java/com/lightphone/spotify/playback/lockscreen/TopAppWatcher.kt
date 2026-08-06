package com.lightphone.spotify.playback.lockscreen

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process

/**
 * Whether LightOS is the app in front.
 *
 * ### Why this needs usage stats
 *
 * Nothing cheaper answers it. `getRunningTasks` has returned only the caller's own tasks since
 * Lollipop, an overlay window is told nothing about what is behind it, and the only free signal —
 * "the screen just came on, and LightOS force-focuses itself on screen-off" — describes the lock
 * screen and says nothing about the other 99% of the time. Trusting it is exactly why the first build
 * of this feature put playback controls over other apps.
 *
 * So: `UsageStatsManager.queryEvents`, gated on the `PACKAGE_USAGE_STATS` appop. LightOS has no
 * Settings screen for that either, so it is a second adb line beside the overlay one:
 *
 * ```
 * adb shell appops set com.lightphone.spotify GET_USAGE_STATS allow
 * ```
 *
 * Ungranted, [isOnLightOs] answers false and the row never appears — the safe direction to fail in.
 *
 * ### Which package is LightOS
 *
 * Resolved rather than hardcoded: whichever package owns `CATEGORY_HOME` is LightOS's, and per the
 * SDK's own `registerLockReceiver` the lock screen is that same "rootActivity (MainActivity in both
 * emulator and real LightOS)". So the launcher package identifies the lock screen too, without this
 * app having to know a name that a LightOS update could change.
 */
class TopAppWatcher(private val context: Context) {

    private val usageStats = context.getSystemService(UsageStatsManager::class.java)

    /** Cached: resolving an intent is not free, and the launcher does not change while running. */
    private val lightOsPackage: String? by lazy { resolveHomePackage() }

    /**
     * The last package the query actually named.
     *
     * An empty window means "nothing has been resumed lately", not "some other app is in front" —
     * and on a lock screen the user is looking at without touching, an empty window is the normal
     * case. Answering null there would take the row away mid-look.
     */
    private var lastKnown: String? = null

    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = runCatching {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isOnLightOs(): Boolean {
        val target = lightOsPackage ?: return false
        return topPackage() == target
    }

    /**
     * The package of the most recently resumed activity.
     *
     * A window rather than a point in time, because `queryEvents` is fed by a batched writer: asking
     * for the last second can come back empty on a screen that has not changed for a while, which
     * would read as "not LightOS" and take the row away for no reason.
     */
    private fun topPackage(): String? {
        val usm = usageStats ?: return null
        val now = System.currentTimeMillis()
        val events = runCatching { usm.queryEvents(now - QUERY_WINDOW_MS, now) }.getOrNull()
            ?: return null
        val event = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latest = event.packageName
            }
        }
        if (latest != null) lastKnown = latest
        return latest ?: lastKnown
    }

    private fun resolveHomePackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return runCatching {
            context.packageManager
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
        }.getOrNull()
    }

    private companion object {
        /** Long enough to survive the batching, short enough that a stale resume cannot dominate. */
        const val QUERY_WINDOW_MS = 60_000L
    }
}
