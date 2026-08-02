package com.lightphone.spotify.playback.download

import android.content.Context
import com.lightphone.spotify.data.TrackMetadata

/**
 * Backend-neutral offline download façade. TIDAL uses Media3; Spotify uses a
 * custom Rust decrypt-to-Ogg pipeline. UI and [AppViewModel] talk only to this.
 */
interface OfflineDownloadCenter {
    val supported: Boolean

    /** Restart unfinished downloads after process death (cold start). */
    fun resumeDownloads(context: Context)

    /** Pin a single track for offline playback. */
    fun download(context: Context, track: TrackMetadata, quality: String)

    fun downloadCollection(
        context: Context,
        collectionUri: String,
        type: String,
        name: String,
        artUrl: String?,
        tracks: List<TrackMetadata>,
        quality: String,
    )

    fun remove(context: Context, track: TrackMetadata, quality: String)

    fun removeCollection(context: Context, collectionUri: String)

    /**
     * Put failed rows back in the queue.
     *
     * Separate from [download] because a failed row already carries its title, artwork and quality —
     * re-deriving them from a screen that may no longer be showing the track is how a retry ends up
     * writing a worse row than the one it replaces. Passing uris also means the caller does not have
     * to hold metadata it does not have: the Downloads screen knows a uri failed, nothing more.
     */
    fun retry(context: Context, trackUris: List<String>)
}

/** No-op center when the active backend does not support offline downloads. */
object NoOpOfflineDownloadCenter : OfflineDownloadCenter {
    override val supported: Boolean = false
    override fun resumeDownloads(context: Context) = Unit
    override fun download(context: Context, track: TrackMetadata, quality: String) = Unit
    override fun downloadCollection(
        context: Context,
        collectionUri: String,
        type: String,
        name: String,
        artUrl: String?,
        tracks: List<TrackMetadata>,
        quality: String,
    ) = Unit
    override fun remove(context: Context, track: TrackMetadata, quality: String) = Unit
    override fun removeCollection(context: Context, collectionUri: String) = Unit
    override fun retry(context: Context, trackUris: List<String>) = Unit
}
