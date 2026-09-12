package com.lightphone.spotify.report

/**
 * Where the app was when it went wrong.
 *
 * A single field rather than anything passed down: the crash handler runs on a dying thread that
 * has no view of the composition, and a report is worth far more with "day" on it than without.
 * Written from the navigation listener, read from anywhere, so it is deliberately volatile.
 *
 * It defaults to [STARTING] and not to a screen name. It used to default to "home" while nothing
 * anywhere ever wrote it, so every report ever filed claimed the crash happened on the home
 * screen — including three that were raised from the player. A field that is always the same
 * answer is worse than no field: it does not just fail to help, it sends whoever reads it to the
 * wrong screen.
 */
object ReportContext {

    /** Before the first destination is reached — the Application and the first composition. */
    const val STARTING = "starting"

    @Volatile
    var screen: String = STARTING
}
