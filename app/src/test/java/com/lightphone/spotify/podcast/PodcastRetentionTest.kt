package com.lightphone.spotify.podcast

import com.lightphone.spotify.data.local.DownloadedTrackEntity
import com.lightphone.spotify.playback.download.DownloadStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retention decides which downloaded episodes get deleted, so the selection is worth pinning down.
 *
 * These cover [PodcastAutoDownload.episodesToDrop] only — the database and file work around it needs
 * an instrumented test, but choosing the wrong rows is the failure that loses the user's audio.
 */
class PodcastRetentionTest {

    private fun row(
        id: String,
        updatedAt: Long,
        state: Int = DownloadStates.COMPLETED,
    ) = DownloadedTrackEntity(
        uri = "spotify:episode:$id",
        title = id,
        artists = "Show",
        album = "Show",
        art_url = null,
        quality = "high",
        state = state,
        bytes = 1_000,
        updated_at = updatedAt,
    )

    private fun idsOf(rows: List<DownloadedTrackEntity>) = rows.map { it.title }

    @Test
    fun `keeps the newest and drops the rest`() {
        val rows = listOf(
            row("oldest", 100),
            row("newest", 400),
            row("middle", 300),
            row("older", 200),
        )

        val dropped = PodcastAutoDownload.episodesToDrop(rows, keep = 2)

        // Newest two survive regardless of the order they arrived in.
        assertEquals(listOf("older", "oldest"), idsOf(dropped))
    }

    @Test
    fun `drops nothing when under the limit`() {
        val rows = listOf(row("a", 100), row("b", 200))

        assertTrue(PodcastAutoDownload.episodesToDrop(rows, keep = 3).isEmpty())
        assertTrue(PodcastAutoDownload.episodesToDrop(rows, keep = 2).isEmpty())
        assertTrue(PodcastAutoDownload.episodesToDrop(emptyList(), keep = 3).isEmpty())
    }

    @Test
    fun `never delete keeps everything`() {
        val rows = (1..20).map { row("e$it", it.toLong()) }

        val dropped = PodcastAutoDownload.episodesToDrop(rows, keep = PodcastRetention.Never.keep)

        assertTrue(dropped.isEmpty())
    }

    /**
     * The reason `prune` runs after enqueueing: a show already at its limit has to be able to take the
     * new episode and lose its oldest. If queued rows did not count, this would drop nothing and the
     * show would sit at four.
     */
    @Test
    fun `a queued episode counts towards the limit and is not itself dropped`() {
        val rows = listOf(
            row("old1", 100),
            row("old2", 200),
            row("old3", 300),
            row("just queued", 400, state = DownloadStates.QUEUED),
        )

        val dropped = PodcastAutoDownload.episodesToDrop(rows, keep = 3)

        assertEquals(listOf("old1"), idsOf(dropped))
    }

    /** A download in progress must survive the prune that its own enqueue triggered. */
    @Test
    fun `an in-progress download is never dropped`() {
        val rows = listOf(
            row("downloading", 500, state = DownloadStates.DOWNLOADING),
            row("a", 100),
            row("b", 200),
        )

        val dropped = PodcastAutoDownload.episodesToDrop(rows, keep = 1)

        assertEquals(listOf("b", "a"), idsOf(dropped))
    }

    /**
     * Failed and removing rows hold no audio to reclaim. Counting them would prune playable episodes
     * to make room for rows that will never play.
     */
    @Test
    fun `failed and removing rows neither count nor get dropped`() {
        val rows = listOf(
            row("failed", 900, state = DownloadStates.FAILED),
            row("removing", 800, state = DownloadStates.REMOVING),
            row("keep me", 300),
            row("keep me too", 200),
            row("drop me", 100),
        )

        val dropped = PodcastAutoDownload.episodesToDrop(rows, keep = 2)

        assertEquals(listOf("drop me"), idsOf(dropped))
    }

    /**
     * The reason ticking twenty episodes of a "Keep 3" show does not delete seventeen of them the
     * next morning. Hand-picked episodes are outside the rule: they neither count towards the limit
     * nor get dropped.
     */
    @Test
    fun `hand-picked episodes are exempt and do not count towards the limit`() {
        val rows = listOf(
            row("picked1", 500),
            row("picked2", 400),
            row("auto newest", 300),
            row("auto middle", 200),
            row("auto oldest", 100),
        )

        val dropped = PodcastAutoDownload.episodesToDrop(
            rows = rows,
            keep = 2,
            keptByHand = setOf("spotify:episode:picked1", "spotify:episode:picked2"),
        )

        // Two automatic episodes survive, the third goes; neither picked episode is touched.
        assertEquals(listOf("auto oldest"), idsOf(dropped))
    }

    @Test
    fun `retention keys round-trip and unknown keys fall back to the default`() {
        PodcastRetention.entries.forEach { retention ->
            assertEquals(retention, PodcastRetention.fromKey(retention.name))
        }
        assertEquals(PodcastRetention.DEFAULT, PodcastRetention.fromKey(null))
        assertEquals(PodcastRetention.DEFAULT, PodcastRetention.fromKey("Keep7"))
    }
}
