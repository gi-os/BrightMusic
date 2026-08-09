package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.lightphone.spotify.ffi.RepeatMode
import com.lightphone.spotify.playback.PlaybackUiState
import com.lightphone.spotify.data.isEpisodeUri
import com.lightphone.spotify.playback.SleepClock
import com.lightphone.spotify.playback.SleepTimer
import com.lightphone.spotify.playback.SleepTimerVisibility
import com.lightphone.spotify.playback.connect.ConnectAliases
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.AuroraBackground
import com.lightphone.spotify.ui.components.PhonoFallbackImage
import com.lightphone.spotify.ui.components.formatTime
import com.lightphone.spotify.ui.components.tapWithLongPress
import com.lightphone.spotify.ui.light.ArtworkSettings
import com.lightphone.spotify.ui.light.ArtworkTreatment
import com.lightphone.spotify.ui.light.rememberArtworkPalette
import com.lightphone.spotify.ui.light.ColorArtworkEffect
import com.lightphone.spotify.podcast.PlaybackSpeed
import com.lightphone.spotify.podcast.PodcastSettings
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.light.legacyNToGridUnits
import com.lightphone.spotify.ui.phono.PhonoHeaderIcon
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.lightTextStyle
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

@Composable
fun PlayingScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenDevices: () -> Unit = {},
    onOpenSleepTimer: () -> Unit = {},
    onAddToPlaylist: ((String) -> Unit)? = null,
) {
    val playback by vm.playback.collectAsState()
    val extras by vm.playingExtras.collectAsState()
    val connect by vm.connect.collectAsState()
    val radio by vm.radio.collectAsState()
    val radioMatch by vm.radioMatch.collectAsState()

    // Live radio has no position, no queue and nothing to shuffle. Rather than showing those
    // controls inert, the player drops to what a stream actually supports: play/pause, and where
    // the sound comes out.
    val isRadio = radio.isActive

    LaunchedEffect(playback.currentUri) {
        vm.refreshPlayingScreen()
    }

    val hasTrack = playback.currentUri != null || playback.title != null

    // Podcasts get a different transport. There is no track to skip to — an episode is loaded on its
    // own — and what you actually want on an hour of speech is to jump back over the bit you missed.
    val isEpisode = playback.currentUri.isEpisodeUri()

    // A jump needs a duration to clamp against, which is also what the scrub bar requires. Read from
    // the stable value, or the buttons would blink out every time the engine reports a zero.
    val knownDurationMs = stableDurationMs(playback)
    val episodeJump = if (isEpisode && knownDurationMs > 0L) SKIP_SECONDS else null

    // On an episode, track skip has nowhere to go. If a jump is not available either, show neither
    // rather than falling back to a pair of buttons that do nothing.
    val showSideControls = !isRadio && (!isEpisode || episodeJump != null)

    // Two layouts, one screen.
    //
    // The main player is what you get whenever there is artwork to show: the track's three lines
    // on the left with a square cover to the
    // right of it. The old text-first layout below is the fallback for the cases it cannot serve
    // — a radio stream (no track and no art) and artwork turned off in Settings.
    val artworkAllowed = ArtworkSettings.showNowPlayingArt &&
        ArtworkSettings.treatment != ArtworkTreatment.OFF
    val useExpanded = hasTrack && artworkAllowed && !isRadio

    // Colours out of the cover for the aurora. Only in COLOR treatment: in the dithered and
    // greyscale modes the panel is deliberately mono, and a grey aurora is just haze.
    val auroraPalette = if (useExpanded && ArtworkSettings.treatment == ArtworkTreatment.COLOR) {
        rememberArtworkPalette(playback.artUrl)
    } else {
        emptyList()
    }

    PhonoScreenShell(
        // Doubles as the cast affordance: shows the device name while remote, so the
        // player always says where the audio is actually going.
        // Aliased here rather than in the controller so a rename shows immediately, instead of on the
        // next device poll.
        title = connect.activeRemoteName
            ?.let { ConnectAliases.nameFor(connect.activeRemoteId, it) }
            ?: " ",
        hideBackButton = false,
        onBack = onBack,
        // The cast control lives in SecondaryControls, not the top bar: PhonoScreenShell
        // gives the back button priority over leftIcon, so a left-slot icon here would
        // silently never render.
        rightIcon = Icons.AutoMirrored.Filled.QueueMusic,
        onRightIconClick = onOpenQueue,
        // No queue on a live stream.
        rightIconVisible = hasTrack && !isRadio,
        horizontalPadding = legacyNToGridDp(20),
        modifier = Modifier.fillMaxSize(),
        backgroundContent = {
            // Full height, behind the top bar and everything else, so it reads as the screen's
            // background rather than a panel sitting in the middle of it.
            AuroraBackground(
                colors = auroraPalette,
                background = LightThemeTokens.colors.background,
                modifier = Modifier.matchParentSize(),
            )
        },
    ) {
        if (useExpanded) {
            ExpandedPlayer(
                playback = playback,
                vm = vm,
                extrasSaved = extras.isTrackSaved,
                savePending = extras.savePending,
                isRemote = connect.isRemote,
                isEpisode = isEpisode,
                episodeJump = episodeJump,
                deviceName = connect.activeRemoteName
                    ?.let { ConnectAliases.nameFor(connect.activeRemoteId, it) },
                onBack = onBack,
                onOpenAlbum = onOpenAlbum,
                onOpenDevices = onOpenDevices,
                onAddToPlaylist = onAddToPlaylist,
            )
            return@PhonoScreenShell
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                // The LPIII is only ~472dp tall, so the cover is sized from what is
                // actually left rather than a fixed dp: the transport controls must never
                // be pushed off-screen by artwork.
                val coverSize = minOf(maxWidth * 0.66f, maxHeight * 0.45f)
                val showCover = hasTrack &&
                    ArtworkSettings.showNowPlayingArt &&
                    ArtworkSettings.treatment != ArtworkTreatment.OFF &&
                    coverSize >= MinCoverSize

                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (showCover) {
                        // Lifts the forced greyscale for as long as this cover is up.
                        ColorArtworkEffect()
                        PhonoFallbackImage(
                            imageUrl = playback.artUrl,
                            contentDescription = playback.title?.let { "Cover art for $it" },
                            // A live channel has no art until the show metadata lands, and a
                            // music note in that gap reads as a missing image. A radio glyph
                            // reads as a station.
                            placeholderIcon = if (isRadio) {
                                Icons.Default.Radio
                            } else {
                                Icons.Default.MusicNote
                            },
                            placeholderIconSize = coverSize * 0.4f,
                            decodeSize = coverSize,
                            modifier = Modifier.size(coverSize),
                        )
                        Spacer(Modifier.height(legacyNToGridDp(16)))
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .padding(bottom = legacyNToGridDp(if (showCover) 10 else 20)),
                    ) {
                        if (hasTrack) {
                            LightText(
                                text = playback.artist.orEmpty(),
                                variant = LightTextVariant.Copy,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                align = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            LightText(
                                text = playback.title.orEmpty(),
                                variant = LightTextVariant.Heading,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                align = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (playback.albumId != null) {
                                            Modifier.lightClickable { playback.albumId?.let(onOpenAlbum) }
                                        } else {
                                            Modifier
                                        }
                                    ),
                            )
                            // Episodes show elapsed and total under the bar instead, where the scrub
                            // thumb is, so the two numbers you need while dragging are together.
                            if (!isRadio && !isEpisode) DurationLabel(playback)
                        } else {
                            LightText(
                                text = "No song playing",
                                variant = LightTextVariant.Copy,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                align = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            LightText(
                                text = "Go back and play something!",
                                variant = LightTextVariant.Detail,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                align = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    // A live stream has no length to scrub through, so there is no bar to show.
                    if (!isRadio) {
                        ProgressBar(
                            playback = playback,
                            onSeek = { vm.seek(it) },
                            showTimes = isEpisode,
                        )
                    }
                    TransportControls(
                        playback = playback,
                        vm = vm,
                        showSkip = showSideControls,
                        seekBySeconds = episodeJump,
                    )
                }
            }

            // Off unless asked for in Settings — but always on screen while a timer is counting, or
            // there would be no way to see it or cancel it. When it is shown it keeps the same place
            // whether or not a timer is running: a row that appears out of nowhere moves the controls
            // under your thumb.
            if ((hasTrack || isRadio) && SleepTimerVisibility.shouldShowLine(SleepTimer.state.armed)) {
                SleepTimerLine(onClick = onOpenSleepTimer)
            }

            if (isRadio) {
                // Just the output picker: shuffle, repeat and Liked Songs are Spotify's, and
                // Spotify Connect cannot carry an NTS stream, so casting is not offered either.
                RadioControls(
                    // Same picker as Spotify: Bluetooth leads the list, so it is the right
                    // destination for radio too.
                    onOpenOutput = onOpenDevices,
                    match = radioMatch,
                    onToggleSaved = vm::toggleRadioTrackSaved,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = legacyNToGridDp(20)),
                )
            } else if (hasTrack) {
                SecondaryControls(
                    playback = playback,
                    extrasSaved = extras.isTrackSaved,
                    savePending = extras.savePending,
                    isRemote = connect.isRemote,
                    // Shuffle has nothing to shuffle on an episode loaded by itself, so the slot
                    // carries the speed control instead — the same repurposing the skip buttons get.
                    // Casting keeps its place: a speaker can play a podcast.
                    episodeSpeed = if (isEpisode) PodcastSettings.episodeSpeed else null,
                    onCycleSpeed = vm::cycleEpisodeSpeed,
                    onOpenDevices = onOpenDevices,
                    onLongPressDevices = {
                        // Long-press jumps straight to the favourite headphones. With none set yet
                        // it opens the picker instead — a long-press that does nothing is
                        // indistinguishable from a missed press.
                        if (!vm.connectFavouriteBluetooth()) onOpenDevices()
                    },
                    onToggleShuffle = vm::toggleShuffle,
                    onToggleRepeat = vm::toggleRepeat,
                    // An episode cannot go in a playlist, so on a podcast the filled state means
                    // "saved — tap to unsave" rather than "tap to add to a playlist". Same control,
                    // the only sensible second action for each kind.
                    onSaveTap = {
                        if (extras.savePending) return@SecondaryControls
                        when {
                            !extras.isTrackSaved -> vm.saveCurrentTrack()
                            isEpisode -> vm.toggleCurrentTrackSave()
                            else -> playback.currentUri?.let { onAddToPlaylist?.invoke(it) }
                        }
                    },
                    saveIsEpisode = isEpisode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = legacyNToGridDp(20)),
                )
            } else {
                Spacer(Modifier.height(legacyNToGridDp(50)))
            }
        }
    }
}

/**
 * The track's length, holding the last real value through the gaps.
 *
 * The engine reports `durationMs = 0` briefly — while loading, and again on some track changes — so
 * anything gated on a known duration flickers if it reads the raw field. Keyed on the uri, so a new
 * track starts from nothing rather than inheriting the previous one's length.
 */
@Composable
private fun stableDurationMs(playback: PlaybackUiState): Long {
    var lastDurationMs by remember(playback.currentUri) { mutableLongStateOf(0L) }
    if (playback.durationMs > 0L) {
        lastDurationMs = playback.durationMs
    }
    return if (playback.durationMs > 0L) playback.durationMs else lastDurationMs
}

@Composable
private fun DurationLabel(playback: PlaybackUiState) {
    val duration = stableDurationMs(playback)
    if (duration <= 0L) return
    LightText(
        text = formatTime(duration),
        variant = LightTextVariant.Detail,
        align = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = legacyNToGridDp(4)),
    )
}

@Composable
private fun ProgressBar(
    playback: PlaybackUiState,
    onSeek: (Long) -> Unit,
    showTimes: Boolean = false,
) {
    val colors = LightThemeTokens.colors
    val duration = stableDurationMs(playback)
    val durationKnown = duration > 0L

    var scrubPositionMs by remember(playback.currentUri) { mutableLongStateOf(-1L) }
    // Hold scrub thumb until backend position catches the seek target (or URI changes).
    LaunchedEffect(playback.currentUri, playback.positionMs, scrubPositionMs) {
        val scrub = scrubPositionMs
        if (scrub < 0L) return@LaunchedEffect
        if (kotlin.math.abs(playback.positionMs - scrub) <= SEEK_SETTLE_MS) {
            scrubPositionMs = -1L
        }
    }
    val displayPositionMs = if (scrubPositionMs >= 0L) scrubPositionMs else playback.positionMs
    val displayProgress = if (durationKnown) {
        (displayPositionMs.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth(0.9f)
            .defaultMinSize(minHeight = legacyNToGridDp(40))
            .then(
                if (durationKnown) {
                    Modifier.pointerInput(duration) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            fun seekAt(x: Float) {
                                val fraction = (x / size.width).coerceIn(0f, 1f)
                                scrubPositionMs = (duration * fraction).toLong()
                            }
                            seekAt(down.position.x)
                            drag(down.id) { change ->
                                change.consume()
                                seekAt(change.position.x)
                            }
                            onSeek(scrubPositionMs.coerceAtLeast(0L))
                            // Keep scrubPositionMs until playback.positionMs settles.
                        }
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(legacyNToGridDp(2))
                .align(Alignment.Center)
                .background(colors.content),
        )
        Box(
            Modifier
                .fillMaxWidth(displayProgress)
                .height(legacyNToGridDp(6))
                .align(Alignment.CenterStart)
                .background(colors.content),
        )
    }
    if (showTimes && durationKnown) {
        // Follows the scrub thumb, not the engine, so a drag tells you where you are about to land.
        // Without this, scrubbing an hour-long episode is guesswork: the bar moves and nothing says
        // where to.
        Row(
            modifier = Modifier.fillMaxWidth(0.9f),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LightText(text = formatTime(displayPositionMs), variant = LightTextVariant.Detail)
            LightText(text = formatTime(duration), variant = LightTextVariant.Detail)
        }
    }
}

private const val SEEK_SETTLE_MS = 750L

/** Matches the SDK's 15-second skip glyphs, so the icon and the behaviour cannot drift apart. */
private const val SKIP_SECONDS = 15

/**
 * Below this the cover is too small to read as artwork and just steals room from the
 * transport row, so the Now Playing screen drops back to the text-only layout. Reached
 * in practice when the IME or a short window height squeezes the player.
 */
private val MinCoverSize = 96.dp

/**
 * Play/pause, flanked by either track skip or a 15-second jump.
 *
 * [seekBySeconds] swaps skip for jump. Podcasts pass it: an episode is loaded on its own, so "next"
 * had nothing to go to, while jumping back over a sentence you missed is the thing you reach for
 * constantly. Music keeps skip, which is what those buttons are for there.
 *
 * The glyphs are the SDK's own `SKIP_BACKWARD_FIFTEEN`/`SKIP_FORWARD_FIFTEEN` — LightOS ships 15-second
 * skip icons, so this reads as part of the system rather than a Material approximation. That is also
 * why the interval is fixed at 15 and not configurable.
 */
@Composable
private fun TransportControls(
    playback: PlaybackUiState,
    vm: AppViewModel,
    showSkip: Boolean = true,
    seekBySeconds: Int? = null,
) {
    val colors = LightThemeTokens.colors
    val iconSize = legacyNToGridDp(40)
    Row(
        modifier = Modifier.padding(top = legacyNToGridDp(12), bottom = legacyNToGridDp(20)),
        horizontalArrangement = Arrangement.spacedBy(legacyNToGridDp(52)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSkip && seekBySeconds != null) {
            LightIcon(
                icon = LightIcons.SKIP_BACKWARD_FIFTEEN,
                // Sized in grid units, not dp: LightIcon appends its own .size(), so a dp modifier
                // here would be overridden and the glyph would not match the play button beside it.
                size = legacyNToGridUnits(40),
                contentDescription = "Back $seekBySeconds seconds",
                modifier = Modifier.lightClickable { vm.seekBy(-seekBySeconds * 1000L) },
            )
        } else if (showSkip) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "Previous",
                tint = colors.content,
                modifier = Modifier
                    .size(iconSize)
                    .lightClickable(onClick = vm::previous),
            )
        }
        // Loading has to look different from idle.
        //
        // This was a single static glyph that read Play or Pause and nothing else, so "the app is
        // fetching audio" and "you pressed play and nothing happened" were pixel-identical — which is
        // how a stuck loading state got reported as "can't press play again". The ring is drawn
        // *around* the button rather than replacing it, so the transport stays where the thumb expects
        // it and stays pressable.
        val busy = playback.isLoading || playback.isBuffering
        Box(contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(
                    color = colors.content,
                    strokeWidth = legacyNToGridDp(2),
                    modifier = Modifier.size(iconSize),
                )
            }
            Icon(
                imageVector = if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = when {
                    busy -> "Loading"
                    playback.isPlaying -> "Pause"
                    else -> "Play"
                },
                tint = colors.content,
                modifier = Modifier
                    .size(if (busy) iconSize * 0.55f else iconSize)
                    .lightClickable(onClick = { if (playback.isPlaying) vm.pause() else vm.resume() }),
            )
        }
        if (showSkip && seekBySeconds != null) {
            LightIcon(
                icon = LightIcons.SKIP_FORWARD_FIFTEEN,
                size = legacyNToGridUnits(40),
                contentDescription = "Forward $seekBySeconds seconds",
                modifier = Modifier.lightClickable { vm.seekBy(seekBySeconds * 1000L) },
            )
        } else if (showSkip) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "Next",
                tint = colors.content,
                modifier = Modifier
                    .size(iconSize)
                    .lightClickable(onClick = vm::next),
            )
        }
    }
}

/**
 * "Sleep timer", or the time left on one.
 *
 * Deliberately a line of text rather than a fifth icon in the row below: the useful thing about a
 * running sleep timer is the number, and a glyph cannot show one. It is set in the Detail variant so
 * it reads as status until there is something to say, at which point it takes the content colour.
 */
@Composable
private fun SleepTimerLine(onClick: () -> Unit) {
    val colors = LightThemeTokens.colors
    val remaining = rememberSleepRemainingMs()
    val armed = SleepTimer.state.armed
    val text = when {
        !armed -> "Sleep timer"
        SleepTimer.state.fading -> "Sleep · fading out"
        SleepTimer.state.endOfItem -> "Sleep · end of this one"
        else -> "Sleep · " + SleepClock.formatRemaining(remaining)
    }
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        color = if (armed) colors.content else colors.contentSecondary,
        align = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(bottom = legacyNToGridDp(6)),
    )
}

/** The only secondary control a live stream has: where the audio goes. */
@Composable
private fun RadioControls(
    onOpenOutput: () -> Unit,
    modifier: Modifier = Modifier,
    /** The Spotify track this station's now-playing line matched, if any. */
    match: AppViewModel.RadioMatch? = null,
    onToggleSaved: () -> Unit = {},
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Shown only once a track has actually been identified. A heart that does nothing on a
        // talk show is worse than no heart: it invites a press and then swallows it.
        if (match != null) {
            PhonoHeaderIcon(
                icon = if (match.saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                onClick = { if (!match.savePending) onToggleSaved() },
                modifier = Modifier.size(legacyNToGridDp(30)),
                contentDescription = if (match.saved) {
                    "Remove from Liked Songs"
                } else {
                    "Save to Liked Songs"
                },
            )
            Spacer(Modifier.width(legacyNToGridDp(36)))
        }
        PhonoHeaderIcon(
            icon = Icons.Default.Bluetooth,
            onClick = onOpenOutput,
            modifier = Modifier.size(legacyNToGridDp(30)),
            contentDescription = "Output",
        )
    }
}

@Composable
private fun SecondaryControls(
    playback: PlaybackUiState,
    extrasSaved: Boolean,
    savePending: Boolean,
    isRemote: Boolean,
    /** Non-null on a podcast episode, where this replaces shuffle. */
    episodeSpeed: Float?,
    onCycleSpeed: () -> Unit,
    onOpenDevices: () -> Unit,
    onLongPressDevices: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onSaveTap: () -> Unit,
    saveIsEpisode: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (episodeSpeed != null) {
            SpeedControl(speed = episodeSpeed, onClick = onCycleSpeed)
        } else {
            PlaybackModeIcon(
                icon = Icons.Default.Shuffle,
                active = playback.shuffleEnabled,
                contentDescription = "Shuffle",
                onClick = onToggleShuffle,
            )
        }
        SaveControl(
            saved = extrasSaved,
            enabled = !savePending,
            isEpisode = saveIsEpisode,
            onClick = onSaveTap,
        )
        PlaybackModeIcon(
            icon = Icons.Default.Cast,
            // Underlined while a speaker owns playback, reusing the same active marker
            // shuffle and repeat use.
            active = isRemote,
            contentDescription = "Play on another device",
            onClick = onOpenDevices,
            onLongClick = onLongPressDevices,
        )
        PlaybackModeIcon(
            icon = when (playback.repeatMode) {
                RepeatMode.TRACK -> Icons.Default.RepeatOne
                else -> Icons.Default.Repeat
            },
            active = playback.repeatMode != RepeatMode.OFF,
            contentDescription = "Repeat",
            onClick = onToggleRepeat,
        )
    }
}

/**
 * The playback-rate button: a label, not a glyph.
 *
 * There is no icon in the SDK set for a speed, and a hand-drawn one would read as decoration rather
 * than a value — the number is the information. It uses the Button variant, which is what the bar
 * labels elsewhere use, so it sits at the same weight as the icons flanking it. Underlined at
 * anything other than 1x, reusing the marker shuffle and repeat already use for "this is on".
 */
@Composable
private fun SpeedControl(speed: Float, onClick: () -> Unit) {
    val colors = LightThemeTokens.colors
    val changed = !PlaybackSpeed.isSame(speed, PlaybackSpeed.NORMAL)
    LightText(
        text = PlaybackSpeed.label(speed),
        variant = LightTextVariant.Button,
        color = if (changed) colors.content else colors.contentSecondary,
        maxLines = 1,
        modifier = Modifier
            .lightClickable(onClick = onClick)
            .padding(legacyNToGridDp(6)),
    )
}

@Composable
private fun SaveControl(
    saved: Boolean,
    enabled: Boolean,
    isEpisode: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    val size = legacyNToGridDp(30)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .then(
                    if (saved) {
                        Modifier.background(colors.content, CircleShape)
                    } else {
                        Modifier.border(legacyNToGridDp(2), colors.content, CircleShape)
                    }
                )
                .lightClickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (saved) Icons.Default.Check else Icons.Default.Add,
                contentDescription = when {
                    saved && isEpisode -> "Remove from saved episodes"
                    saved -> "Add to playlists"
                    isEpisode -> "Save episode"
                    else -> "Save to Liked Songs"
                },
                tint = if (saved) colors.background else colors.content,
                modifier = Modifier.size(legacyNToGridDp(20)),
            )
        }
        Spacer(Modifier.height(legacyNToGridDp(4)))
        Spacer(Modifier.height(legacyNToGridDp(2)))
    }
}

@Composable
private fun PlaybackModeIcon(
    icon: ImageVector,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LightThemeTokens.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(legacyNToGridDp(30))
                .tapWithLongPress(onClick = onClick, onLongClick = onLongClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = colors.content,
                modifier = Modifier.size(legacyNToGridDp(30)),
            )
        }
        Spacer(Modifier.height(legacyNToGridDp(4)))
        if (active) {
            Box(
                Modifier
                    .width(legacyNToGridDp(12))
                    .height(legacyNToGridDp(2))
                    .background(colors.content),
            )
        } else {
            Spacer(Modifier.height(legacyNToGridDp(2)))
        }
    }
}

/**
 * Now Playing, in the shape LightOS uses everywhere else: text on the left, a square thumbnail
 * on the right, on the theme's own background.
 *
 * The full-bleed cover is gone, and with it the whole problem it created. Artwork behind text
 * meant a scrim, the scrim meant guessing whether the ink should be black or white, and none of
 * it looked like the rest of the phone. Here the background is the background, so the text is
 * simply the content colour and always reads.
 *
 * The art is square and exactly as tall as the three lines beside it. That comes from
 * `IntrinsicSize.Min` on the row plus `fillMaxHeight().aspectRatio(1f)` on the image — the row
 * measures itself against its tallest child, which is the text column, and the cover then takes
 * that height and matches its width to it. A hard-coded size would drift out of alignment the
 * moment the type scale changed.
 *
 * Below it: the scrub bar with elapsed and remaining, previous / play-pause / next, then
 * shuffle, repeat, save and output. Opacity still carries on/off, at [InactiveControlAlpha].
 */
@Composable
private fun ColumnScope.ExpandedPlayer(
    playback: PlaybackUiState,
    vm: AppViewModel,
    extrasSaved: Boolean,
    savePending: Boolean,
    isRemote: Boolean,
    isEpisode: Boolean,
    episodeJump: Int?,
    deviceName: String?,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenDevices: () -> Unit,
    onAddToPlaylist: ((String) -> Unit)?,
) {
    val ink = LightThemeTokens.colors.content
    // Holds the panel in colour for as long as the cover is on screen.
    ColorArtworkEffect()

    // A touch larger than the SDK's list sizes, not half again: 1.5x crowded the screen. The
    // *spacing* between the three lines carries the height instead, which keeps the cover large
    // — its height is derived from this block (see below), so shrinking the type alone would
    // have shrunk the artwork with it.
    val textScale = 1.15f

    BoxWithConstraints(
        Modifier
            .weight(1f)
            .fillMaxWidth(),
    ) {
        val u = maxWidth / DesignWidthPx



        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // The row is as tall as its tallest child — the text — and the cover reads
                    // that height off it.
                    .height(IntrinsicSize.Min)
                    // Swipes live on the track block, not the whole screen: the scrub bar below
                    // owns horizontal drags, and a player that skipped a song when you meant to
                    // seek would be worse than one with no gestures at all.
                    .playerGestures(
                        onSwipeDown = onBack,
                        onSwipeLeft = vm::next,
                        onSwipeRight = vm::previous,
                    )
                    .padding(bottom = u * 40f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = u * 28f),
                    verticalArrangement = Arrangement.spacedBy(u * 30f),
                ) {
                    ScaledMarqueeText(
                        text = playback.title.orEmpty(),
                        variant = LightTextVariant.Heading,
                        color = ink,
                        scale = textScale,
                    )
                    ScaledMarqueeText(
                        text = playback.artist.orEmpty(),
                        variant = LightTextVariant.Copy,
                        color = ink,
                        scale = textScale,
                    )
                    ScaledMarqueeText(
                        text = playback.album.orEmpty(),
                        variant = LightTextVariant.Detail,
                        color = ink.copy(alpha = 0.6f),
                        scale = textScale,
                    )
                }
                // Cross-fades on a track change, so a skip reads as the next record arriving.
                Crossfade(
                    targetState = playback.artUrl,
                    animationSpec = tween(durationMillis = 320),
                    label = "cover-change",
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f),
                ) { url ->
                    PhonoFallbackImage(
                        imageUrl = url,
                        contentDescription = playback.title?.let { "Cover art for $it" },
                        placeholderIcon = Icons.Default.MusicNote,
                        placeholderIconSize = u * 60f,
                        decodeSize = u * 320f,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (playback.albumId != null) {
                                    Modifier.lightClickable {
                                        playback.albumId?.let(onOpenAlbum)
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }

            PlayerScrubBar(playback = playback, onSeek = vm::seek, u = u, ink = ink)

            Spacer(Modifier.height(u * 24f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerGlyph(
                    icon = if (episodeJump != null) null else Icons.Default.SkipPrevious,
                    lightIcon = if (episodeJump != null) LightIcons.SKIP_BACKWARD_FIFTEEN else null,
                    contentDescription = if (episodeJump != null) "Back 15 seconds" else "Previous",
                    boxSize = u * 132f,
                    glyphSize = u * 56f,
                    ink = ink,
                    onClick = {
                        if (episodeJump != null) vm.seekBy(-episodeJump * 1000L) else vm.previous()
                    },
                )
                Box(
                    modifier = Modifier
                        .size(u * 160f)
                        .lightClickable {
                            if (playback.isPlaying) vm.pause() else vm.resume()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // Only while loading, so it reads as activity rather than decoration:
                    // "fetching" and "you pressed play and nothing happened" must not look alike.
                    if (playback.isLoading || playback.isBuffering) {
                        CircularProgressIndicator(
                            color = ink,
                            strokeWidth = u * 3f,
                            modifier = Modifier.size(u * 160f),
                        )
                    }
                    Icon(
                        imageVector = if (playback.isPlaying) {
                            Icons.Default.Pause
                        } else {
                            Icons.Default.PlayArrow
                        },
                        contentDescription = if (playback.isPlaying) "Pause" else "Play",
                        tint = ink,
                        modifier = Modifier.size(u * 76f),
                    )
                }
                PlayerGlyph(
                    icon = if (episodeJump != null) null else Icons.Default.SkipNext,
                    lightIcon = if (episodeJump != null) LightIcons.SKIP_FORWARD_FIFTEEN else null,
                    contentDescription = if (episodeJump != null) "Forward 15 seconds" else "Next",
                    boxSize = u * 132f,
                    glyphSize = u * 56f,
                    ink = ink,
                    onClick = {
                        if (episodeJump != null) vm.seekBy(episodeJump * 1000L) else vm.next()
                    },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = u * 26f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isEpisode) {
                    // An episode loaded on its own has nothing to shuffle, so the slot carries
                    // the playback speed instead.
                    Box(
                        modifier = Modifier
                            .size(u * 112f)
                            .lightClickable(onClick = vm::cycleEpisodeSpeed),
                        contentAlignment = Alignment.Center,
                    ) {
                        LightText(
                            text = PlaybackSpeed.label(PodcastSettings.episodeSpeed),
                            variant = LightTextVariant.Button,
                            monospace = true,
                            color = ink.copy(
                                alpha = if (
                                    PlaybackSpeed.isSame(
                                        PodcastSettings.episodeSpeed,
                                        PlaybackSpeed.NORMAL,
                                    )
                                ) {
                                    InactiveControlAlpha
                                } else {
                                    1f
                                },
                            ),
                        )
                    }
                } else {
                    PlayerGlyph(
                        icon = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        boxSize = u * 112f,
                        glyphSize = u * 52f,
                        ink = ink,
                        active = playback.shuffleEnabled,
                        onClick = vm::toggleShuffle,
                    )
                }
                PlayerGlyph(
                    icon = when (playback.repeatMode) {
                        RepeatMode.TRACK -> Icons.Default.RepeatOne
                        else -> Icons.Default.Repeat
                    },
                    contentDescription = "Repeat",
                    boxSize = u * 112f,
                    glyphSize = u * 52f,
                    ink = ink,
                    active = playback.repeatMode != RepeatMode.OFF,
                    onClick = vm::toggleRepeat,
                )
                PlayerGlyph(
                    icon = if (extrasSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (extrasSaved) "Saved" else "Save",
                    boxSize = u * 112f,
                    glyphSize = u * 52f,
                    ink = ink,
                    active = extrasSaved,
                    onClick = {
                        if (savePending) return@PlayerGlyph
                        when {
                            !extrasSaved -> vm.saveCurrentTrack()
                            isEpisode -> vm.toggleCurrentTrackSave()
                            else -> playback.currentUri?.let { onAddToPlaylist?.invoke(it) }
                        }
                    },
                )
                PlayerGlyph(
                    icon = Icons.Default.Bluetooth,
                    contentDescription = "Play on another device",
                    boxSize = u * 112f,
                    glyphSize = u * 52f,
                    ink = ink,
                    // Lit whenever the sound is leaving the phone — headphones or a Connect
                    // speaker, not Connect alone.
                    active = isRemote || playback.externalOutput,
                    onClick = onOpenDevices,
                    onLongClick = {
                        if (!vm.connectFavouriteBluetooth()) onOpenDevices()
                    },
                )
            }

            deviceName?.let { name ->
                LightText(
                    text = name.uppercase(),
                    variant = LightTextVariant.Micro,
                    monospace = true,
                    color = ink.copy(alpha = 0.66f),
                    align = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = u * 16f),
                )
            }
        }
    }
}


/** Opacity of an off control, from the design. */
private const val InactiveControlAlpha = 0.42f

/** The mockup's canvas width. Every size in the player is a fraction of it. */
private const val DesignWidthPx = 1080f

/**
 * One round tap target with a glyph in it, at the design's sizes.
 *
 * [active] drives opacity rather than a colour or an underline, which is how the design marks
 * state and is the only marker that survives on top of arbitrary artwork.
 */
@Composable
private fun PlayerGlyph(
    contentDescription: String,
    boxSize: Dp,
    glyphSize: Dp,
    ink: Color,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    lightIcon: com.thelightphone.sdk.ui.LightIconConfiguration? = null,
    active: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    val tint = ink.copy(alpha = if (active) 1f else InactiveControlAlpha)
    Box(
        modifier = Modifier
            .size(boxSize)
            .tapWithLongPress(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            lightIcon != null -> LightIcon(
                icon = lightIcon,
                size = legacyNToGridUnits(40),
                tint = tint,
                contentDescription = contentDescription,
            )
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(glyphSize),
            )
        }
    }
}

/**
 * The design's scrub bar: a thin track, a white fill, a round thumb, and the two times beneath.
 *
 * The drag handling is the same as the fallback player's — the thumb is held at the dragged
 * position until the engine's reported position catches up, because a seek is asynchronous and
 * for about a second afterwards the engine still reports where the track *was*.
 */
@Composable
private fun PlayerScrubBar(
    playback: PlaybackUiState,
    onSeek: (Long) -> Unit,
    u: Dp,
    ink: Color,
) {
    val duration = stableDurationMs(playback)
    val durationKnown = duration > 0L

    var scrubPositionMs by remember(playback.currentUri) { mutableLongStateOf(-1L) }
    LaunchedEffect(playback.currentUri, playback.positionMs, scrubPositionMs) {
        val scrub = scrubPositionMs
        if (scrub < 0L) return@LaunchedEffect
        if (kotlin.math.abs(playback.positionMs - scrub) <= SEEK_SETTLE_MS) {
            scrubPositionMs = -1L
        }
    }
    val positionMs = if (scrubPositionMs >= 0L) scrubPositionMs else playback.positionMs
    val progress = if (durationKnown) {
        (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(verticalArrangement = Arrangement.spacedBy(u * 18f)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(u * 44f)
                .then(
                    if (durationKnown) {
                        Modifier.pointerInput(duration) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                fun seekAt(x: Float) {
                                    val fraction = (x / size.width).coerceIn(0f, 1f)
                                    scrubPositionMs = (duration * fraction).toLong()
                                }
                                seekAt(down.position.x)
                                drag(down.id) { change ->
                                    change.consume()
                                    seekAt(change.position.x)
                                }
                                onSeek(scrubPositionMs.coerceAtLeast(0L))
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            val trackWidth = maxWidth
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(u * 4f)
                    .background(ink.copy(alpha = 0.3f)),
            )
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(u * 4f)
                    .background(ink),
            )
            Box(
                Modifier
                    .offset(x = trackWidth * progress - u * 11f)
                    .size(u * 22f)
                    .background(ink, CircleShape),
            )
        }
        if (durationKnown) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LightText(
                    text = formatTime(positionMs),
                    variant = LightTextVariant.Detail,
                    monospace = true,
                    color = ink.copy(alpha = 0.72f),
                )
                LightText(
                    // Time remaining, not total: the design's, and the more useful of the two
                    // when you are deciding whether to skip.
                    text = "-" + formatTime((duration - positionMs).coerceAtLeast(0L)),
                    variant = LightTextVariant.Detail,
                    monospace = true,
                    color = ink.copy(alpha = 0.72f),
                )
            }
        }
    }
}

/**
 * One line of the now-playing block: scaled up, and scrolled sideways when it does not fit.
 *
 * `basicMarquee` only animates when the text actually overflows, so a short title sits still —
 * which is what makes this read as an old player's display rather than a screen that fidgets.
 * `maxLines = 1` is required for it: a wrapping line has nothing to scroll.
 *
 * The scale goes on the *font size* rather than a `graphicsLayer`, so the glyphs are laid out
 * and hinted at their real size instead of being magnified after rasterising.
 */
@Composable
private fun ScaledMarqueeText(
    text: String,
    variant: LightTextVariant,
    color: Color,
    scale: Float,
) {
    val base = lightTextStyle(variant)
    Text(
        text = text,
        color = color,
        style = base.copy(fontSize = base.fontSize * scale, lineHeight = base.lineHeight * scale),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .fillMaxWidth()
            .basicMarquee(
                iterations = Int.MAX_VALUE,
                initialDelayMillis = MarqueeDelayMs,
                repeatDelayMillis = MarqueeDelayMs,
            ),
    )
}

/** A beat to read the line before it starts moving, and again between passes. */
private const val MarqueeDelayMs = 2_000

/**
 * Swipe down to close, swipe across to change track.
 *
 * A tap is deliberately *not* handled here: the artwork under this opens the album, and two
 * detectors on the same element race — the loser being whichever the pointer barely moved for.
 * The child's own click wins any gesture that never became a drag, which is the right outcome.
 *
 * Axis is decided from the accumulated deltas at the end of the gesture rather than at the first
 * event, so a slightly diagonal flick still does what it looked like.
 */
private fun Modifier.playerGestures(
    onSwipeDown: () -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
): Modifier = pointerInput(Unit) {
    val threshold = SwipeThreshold.toPx()
    var dx = 0f
    var dy = 0f
    // detectDragGestures, not a hand-rolled awaitFirstDown + drag: the artwork inside this row has
    // its own clickable, which claims the pointer as a press before any movement is reported, and
    // the hand-rolled version therefore saw no drag at all — swipes did nothing. This waits out
    // touch slop first, at which point the gesture is unambiguously a drag and the click is
    // cancelled for us.
    detectDragGestures(
        onDragStart = {
            dx = 0f
            dy = 0f
        },
        onDragEnd = {
            val absX = kotlin.math.abs(dx)
            val absY = kotlin.math.abs(dy)
            when {
                absX > absY && absX > threshold -> if (dx > 0) onSwipeRight() else onSwipeLeft()
                // Down only: an upward flick has nothing to do here, and closing on it would make
                // the player feel like it dismisses itself at random.
                dy > threshold -> onSwipeDown()
            }
        },
    ) { change, dragAmount ->
        change.consume()
        dx += dragAmount.x
        dy += dragAmount.y
    }
}

private val SwipeThreshold = 56.dp
