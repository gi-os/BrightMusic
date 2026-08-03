package com.lightphone.spotify.playback.download

/**
 * What a scheduled library check should actually download, worked out without touching a database,
 * a network or an Android class so it can be tested on the host.
 *
 * The rules here exist because an automatic downloader is the one part of the app that spends the
 * user's storage and data without being asked each time. Every decision below is a limit.
 */
object AutoPinPlan {

    /**
     * Nothing auto-pinned may grow without a ceiling.
     *
     * Liked Songs is the collection most likely to be enormous — a library built over ten years is
     * routinely several thousand tracks, which at 320 kbps is tens of gigabytes and more than the
     * phone has. So the newest [limit] are kept and the rest are not, which matches what the setting
     * promises: recent liked music, available offline.
     */
    fun likedToPin(
        likedUrisNewestFirst: List<String>,
        alreadyPinned: Set<String>,
        limit: Int,
    ): List<String> {
        if (limit <= 0) return emptyList()
        return likedUrisNewestFirst.take(limit).filter { it !in alreadyPinned }
    }

    /**
     * Liked tracks pinned by this feature that have fallen outside the window.
     *
     * A rolling window has to drop from the far end or it is not a window — without this, unliking a
     * track or liking a hundred new ones would leave the old audio on the phone for good, and the
     * "keep 50" setting would describe only how fast the collection grows.
     *
     * Takes what is currently pinned *for this collection* rather than everything on the phone, so a
     * track the user downloaded deliberately somewhere else is never dropped by a rule about Liked
     * Songs. Membership of another collection is checked by the caller against the download tables.
     */
    fun likedToDrop(
        likedUrisNewestFirst: List<String>,
        pinnedForLiked: Set<String>,
        limit: Int,
    ): List<String> {
        val keep = likedUrisNewestFirst.take(maxOf(limit, 0)).toSet()
        return pinnedForLiked.filter { it !in keep }
    }

    /**
     * Whether a Daily Mix's contents have moved on from what is pinned.
     *
     * Daily Mixes are regenerated rather than edited, so the useful comparison is set membership,
     * not order or count: the same tracks in a different sequence is the same mix, and re-downloading
     * it would burn a phone's worth of data on nothing.
     */
    fun mixChanged(currentUris: List<String>, pinnedUris: Set<String>): Boolean =
        currentUris.toSet() != pinnedUris

    /**
     * How many mixes to keep, in the order Spotify returned them.
     *
     * Spotify generates six Daily Mixes and a handful of other Made-For-You playlists, and pinning
     * all of them is around three hours of audio a day. Two is a commute.
     */
    fun mixesToPin(
        mixIds: List<String>,
        limit: Int,
    ): List<String> = if (limit <= 0) emptyList() else mixIds.take(limit)

    /** Ceilings. Exposed so the settings screen and the tests agree on the same numbers. */
    const val DEFAULT_LIKED_LIMIT = 50
    const val DEFAULT_MIX_LIMIT = 2
    val LIKED_LIMIT_CHOICES = listOf(0, 25, 50, 100, 200)
}
