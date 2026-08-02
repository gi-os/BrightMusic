package com.lightphone.spotify.podcast

/**
 * Which slice of a show's feed to fetch next, in the order the screen is showing it.
 *
 * Spotify's `/shows/{id}/episodes` is always newest-first: display position 0 is API offset 0, and
 * there is no `sort` parameter. Oldest-first is therefore not a different request but the same feed
 * read from the other end — display position `i` is API offset `total - 1 - i` — so a page of the
 * oldest episodes is the window ending at the far end of the feed, fetched and then reversed.
 *
 * Doing it that way rather than pulling the whole feed down and sorting locally is the whole point:
 * a daily show that has run for five years is thousands of episodes, which is minutes of waiting and
 * megabytes of JSON before anything appears on screen. This way the first screenful of the *oldest*
 * episodes costs exactly one page, same as the newest.
 *
 * Pure Kotlin with no Android imports, so the offset arithmetic — the part that silently skips or
 * repeats episodes when it is wrong — is unit-testable without a device.
 */
object EpisodePaging {

    /** Spotify's own ceiling for this endpoint, and what a page costs. */
    const val PAGE_SIZE = 50

    /**
     * Size of the request that exists only to read `total` out of the envelope.
     *
     * Oldest-first cannot compute its first offset without knowing how long the feed is, and asking
     * for a full page to find out would fetch the newest 50 episodes — precisely the ones this order
     * shows last.
     */
    const val PROBE_LIMIT = 1

    data class PageRequest(val offset: Int, val limit: Int)

    /**
     * True when [nextRequest] cannot be answered yet because the feed's length is unknown.
     * Resolve it with a [PROBE_LIMIT]-sized request and call [nextRequest] again.
     */
    fun needsTotal(total: Int, oldestFirst: Boolean): Boolean = oldestFirst && total <= 0

    /**
     * The next page to ask for, or null when everything is loaded.
     *
     * [total] of 0 means "not known yet", which only newest-first can act on — it just asks for the
     * first page and reads the total off the response.
     */
    fun nextRequest(
        loaded: Int,
        total: Int,
        oldestFirst: Boolean,
        pageSize: Int = PAGE_SIZE,
    ): PageRequest? {
        if (pageSize <= 0) return null
        if (total <= 0) {
            return if (loaded == 0 && !oldestFirst) PageRequest(0, pageSize) else null
        }
        if (loaded >= total) return null
        val remaining = total - loaded
        val limit = minOf(pageSize, remaining)
        // Newest-first walks forward from 0; oldest-first walks backward from the end, so each page
        // starts one page-width earlier than the last one ended.
        return if (oldestFirst) PageRequest(remaining - limit, limit) else PageRequest(loaded, limit)
    }

    /**
     * Add a fetched page to what is on screen, in display order.
     *
     * The page arrives newest-first whichever way the list is sorted, so oldest-first reverses it.
     * Ids already loaded are dropped: a show that publishes an episode between two page fetches
     * shifts every offset by one, and without this that boundary episode appears twice — which
     * `LazyColumn` turns into a duplicate-key crash rather than a cosmetic repeat.
     */
    fun <T> merge(
        loaded: List<T>,
        page: List<T>,
        oldestFirst: Boolean,
        id: (T) -> String,
    ): List<T> {
        if (page.isEmpty()) return loaded
        val ordered = if (oldestFirst) page.reversed() else page
        val seen = HashSet<String>(loaded.size + ordered.size)
        loaded.forEach { seen.add(id(it)) }
        return loaded + ordered.filter { seen.add(id(it)) }
    }
}
