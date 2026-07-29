package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightphone.spotify.ffi.RepeatMode
import com.lightphone.spotify.playback.PlaybackUiState
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.PhonoFallbackImage
import com.lightphone.spotify.ui.components.formatTime
import com.lightphone.spotify.ui.light.ArtworkSettings
import com.lightphone.spotify.ui.light.ArtworkTreatment
import com.lightphone.spotify.ui.light.ColorArtworkEffect
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoHeaderIcon
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightText
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
    onAddToPlaylist: ((String) -> Unit)? = null,
) {
    val playback by vm.playback.collectAsState()
    val extras by vm.playingExtras.collectAsState()
    val connect by vm.connect.collectAsState()

    LaunchedEffect(playback.currentUri) {
        vm.refreshPlayingScreen()
    }

    val hasTrack = playback.currentUri != null || playback.title != null

    PhonoScreenShell(
        // Doubles as the cast affordance: shows the device name while remote, so the
        // player always says where the audio is actually going.
        title = connect.activeRemoteName ?: " ",
        hideBackButton = false,
        onBack = onBack,
        // The cast control lives in SecondaryControls, not the top bar: PhonoScreenShell
        // gives the back button priority over leftIcon, so a left-slot icon here would
        // silently never render.
        rightIcon = Icons.AutoMirrored.Filled.QueueMusic,
        onRightIconClick = onOpenQueue,
        rightIconVisible = hasTrack,
        horizontalPadding = legacyNToGridDp(20),
        modifier = Modifier.fillMaxSize(),
    ) {
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
                            placeholderIconSize = coverSize * 0.4f,
                            decodeSize = coverSize,
                            modifier = Modifier
                                .size(coverSize)
                                .then(
                                    if (playback.albumId != null) {
                                        Modifier.lightClickable { playback.albumId?.let(onOpenAlbum) }
                                    } else {
                                        Modifier
                                    }
                                ),
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
                            DurationLabel(playback)
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

                    ProgressBar(playback, onSeek = { vm.seek(it) })
                    TransportControls(playback, vm)
                }
            }

            if (hasTrack) {
                SecondaryControls(
                    playback = playback,
                    extrasSaved = extras.isTrackSaved,
                    savePending = extras.savePending,
                    isRemote = connect.isRemote,
                    onOpenDevices = onOpenDevices,
                    onToggleShuffle = vm::toggleShuffle,
                    onToggleRepeat = vm::toggleRepeat,
                    onSaveTap = {
                        if (extras.savePending) return@SecondaryControls
                        if (extras.isTrackSaved) {
                            playback.currentUri?.let { onAddToPlaylist?.invoke(it) }
                        } else {
                            vm.saveCurrentTrack()
                        }
                    },
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

@Composable
private fun DurationLabel(playback: PlaybackUiState) {
    var lastDurationMs by remember(playback.currentUri) { mutableLongStateOf(0L) }
    if (playback.durationMs > 0L) {
        lastDurationMs = playback.durationMs
    }
    val duration = if (playback.durationMs > 0L) playback.durationMs else lastDurationMs
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
private fun ProgressBar(playback: PlaybackUiState, onSeek: (Long) -> Unit) {
    val colors = LightThemeTokens.colors
    var lastDurationMs by remember(playback.currentUri) { mutableLongStateOf(0L) }
    if (playback.durationMs > 0L) {
        lastDurationMs = playback.durationMs
    }
    val duration = if (playback.durationMs > 0L) playback.durationMs else lastDurationMs
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
}

private const val SEEK_SETTLE_MS = 750L

/**
 * Below this the cover is too small to read as artwork and just steals room from the
 * transport row, so the Now Playing screen drops back to the text-only layout. Reached
 * in practice when the IME or a short window height squeezes the player.
 */
private val MinCoverSize = 96.dp

@Composable
private fun TransportControls(playback: PlaybackUiState, vm: AppViewModel) {
    val colors = LightThemeTokens.colors
    val iconSize = legacyNToGridDp(40)
    Row(
        modifier = Modifier.padding(top = legacyNToGridDp(12), bottom = legacyNToGridDp(20)),
        horizontalArrangement = Arrangement.spacedBy(legacyNToGridDp(52)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.SkipPrevious,
            contentDescription = "Previous",
            tint = colors.content,
            modifier = Modifier
                .size(iconSize)
                .lightClickable(onClick = vm::previous),
        )
        Icon(
            imageVector = if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = "Play/Pause",
            tint = colors.content,
            modifier = Modifier
                .size(iconSize)
                .lightClickable(onClick = { if (playback.isPlaying) vm.pause() else vm.resume() }),
        )
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

@Composable
private fun SecondaryControls(
    playback: PlaybackUiState,
    extrasSaved: Boolean,
    savePending: Boolean,
    isRemote: Boolean,
    onOpenDevices: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onSaveTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaybackModeIcon(
            icon = Icons.Default.Shuffle,
            active = playback.shuffleEnabled,
            contentDescription = "Shuffle",
            onClick = onToggleShuffle,
        )
        SaveControl(
            saved = extrasSaved,
            enabled = !savePending,
            onClick = onSaveTap,
        )
        PlaybackModeIcon(
            icon = Icons.Default.Cast,
            // Underlined while a speaker owns playback, reusing the same active marker
            // shuffle and repeat use.
            active = isRemote,
            contentDescription = "Play on another device",
            onClick = onOpenDevices,
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

@Composable
private fun SaveControl(
    saved: Boolean,
    enabled: Boolean,
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
                contentDescription = if (saved) "Add to playlists" else "Save to Liked Songs",
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
) {
    val colors = LightThemeTokens.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PhonoHeaderIcon(
            icon = icon,
            onClick = onClick,
            modifier = Modifier.size(legacyNToGridDp(30)),
            contentDescription = contentDescription,
        )
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
