package com.lightphone.spotify.playback

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.lightphone.spotify.data.isEpisodeUri
import com.lightphone.spotify.podcast.PlaybackSpeed
import com.lightphone.spotify.podcast.PodcastSettings
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Bridges Media3's [Player] surface (for the MediaSession / lock-screen controls)
 * onto the native librespot engine via [PlaybackController]. Audio itself is
 * produced in Rust by rodio; this player only mirrors state and forwards
 * transport commands.
 *
 * Everything the lock screen shows comes from here. The system builds its media controls from the
 * commands this player advertises, so a command left out of [getState] is a button that does not exist
 * — which is why the seek commands and the timeline command are declared even though the in-app player
 * never calls them.
 */
class LibrespotPlayer(
    private val controller: PlaybackController,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        // Rust player events arrive on a background thread; Media3 requires main looper.
        controller.onStateChanged = { mainHandler.post { invalidateState() } }
        // Push current timeline/playWhenReady so Media3 can promote FGS immediately.
        invalidateState()
    }

    override fun getState(): State {
        val s = controller.state.value
        val isEpisode = s.currentUri.isEpisodeUri()

        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                // Not load-bearing — Media3 already synthesises a single-window timeline from
                // COMMAND_GET_CURRENT_MEDIA_ITEM, so duration and progress were available without it.
                // Declared because it is true: there is exactly one item and its length is known.
                Player.COMMAND_GET_TIMELINE,
                // The real rewind/fast-forward pair. Distinct from the skip commands above: a headset
                // or car head unit maps its own buttons to these, and they are what carry the 15-second
                // increments declared below.
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_STOP,
            )
            .build()

        val playbackState = when {
            s.currentUri == null -> Player.STATE_IDLE
            s.isLoading || s.isBuffering -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }

        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlaybackState(playbackState)
            .setPlayWhenReady(s.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            // Only meaningful outside IDLE/ENDED: State.Builder asserts on a loading player with no
            // item, which the engine can produce by reporting onLoading before onTrackChanged.
            .setIsLoading(s.isLoading && playbackState != Player.STATE_IDLE)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            // A plain number, deliberately. SimpleBasePlayer turns it into an extrapolating supplier
            // itself once the player is READY and playing, so the lock screen's bar advances between
            // the engine's once-a-second reports — and it does *not* extrapolate while buffering,
            // which doing this by hand would have got wrong.
            .setContentPositionMs(s.positionMs)
            // The rate has to be declared for that extrapolation to be right. Media3 advances the
            // position by elapsed wall-clock time multiplied by this, so an episode at 1.5x with the
            // rate left at 1.0 would have a lock-screen bar running two thirds as fast as the audio
            // and snapping forward at every report from the engine.
            .setPlaybackParameters(
                PlaybackParameters(
                    if (isEpisode) PlaybackSpeed.sanitize(PodcastSettings.episodeSpeed) else 1f,
                ),
            )

        if (s.currentUri != null) {
            val metadata = MediaMetadata.Builder()
                .setTitle(s.title ?: "")
                .setArtist(s.artist ?: "")
                // Marks the session as a podcast where the system cares, and it costs nothing where it
                // does not.
                .setMediaType(
                    if (isEpisode) {
                        MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
                    } else {
                        MediaMetadata.MEDIA_TYPE_MUSIC
                    },
                )
                .apply { s.artUrl?.let { setArtworkUri(Uri.parse(it)) } }
                .build()
            val item = MediaItem.Builder()
                .setUri(s.currentUri)
                .setMediaId(s.currentUri)
                .setMediaMetadata(metadata)
                .build()
            val durationUs = if (s.durationMs > 0) s.durationMs * 1000 else C.TIME_UNSET
            val data = MediaItemData.Builder(s.currentUri)
                .setMediaItem(item)
                .setDurationUs(durationUs)
                .build()
            builder.setPlaylist(listOf(data))
        }

        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controller.resume() else controller.pause()
        return Futures.immediateVoidFuture()
    }

    /**
     * Transport from outside the app: the lock screen, a headset, a car.
     *
     * On a podcast episode the skip commands are re-pointed at a 15-second jump. An episode is loaded
     * on its own, so "next track" had nothing to go to and the button did nothing; jumping is both what
     * the in-app player now does and the only useful meaning those buttons can carry here.
     */
    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        val s = controller.state.value
        val isEpisode = s.currentUri.isEpisodeUri()
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                if (isEpisode) seekTo(s, s.positionMs + SEEK_INCREMENT_MS) else controller.next()
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                if (isEpisode) seekTo(s, s.positionMs - SEEK_INCREMENT_MS) else controller.previous()
            // The lock screen's rewind/forward buttons land here, with the target already computed
            // from the increments above. Media3 clamps that target to *exactly* the duration, and
            // seeking to the duration ends the item — so "forward 15 seconds" near the end would be
            // a stop. Clamped again for that reason.
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD -> seekTo(s, positionMs)
            // A scrub. Passed through: dragging to the very end is a deliberate "finish this", unlike
            // a jump button.
            else -> controller.seek(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    /**
     * Seek, keeping a second clear of the end.
     *
     * The controller floors a seek at zero, so only the upper bound needs handling: landing exactly on
     * the duration is what ends an item, and no jump button should be able to stop playback.
     */
    private fun seekTo(state: PlaybackUiState, positionMs: Long) {
        val duration = state.durationMs
        val target = if (duration > 0L) {
            positionMs.coerceAtMost((duration - 1_000L).coerceAtLeast(0L))
        } else {
            positionMs
        }
        controller.seek(target)
    }

    override fun handleStop(): ListenableFuture<*> {
        controller.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        controller.onStateChanged = null
        return Futures.immediateVoidFuture()
    }

    private companion object {
        /** Matches the in-app player's jump and the SDK's 15-second glyphs. */
        const val SEEK_INCREMENT_MS = 15_000L
    }
}
