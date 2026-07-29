package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Radio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.lightphone.spotify.radio.NtsStreams
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.PhonoMediaListItem
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant

/**
 * NTS Radio: two live channels and the mixtape stations.
 *
 * Uses the same rows and the same Now Playing screen as the Spotify side, which is the point — the
 * transport, the cover and the output picker all work identically once a stream is on, because radio
 * state is overlaid onto the shared playback state rather than given a player of its own.
 *
 * Catalogue and metadata endpoints come from [vandamd/nts-radio](https://github.com/vandamd/nts-radio).
 */
@Composable
fun RadioScreen(
    vm: AppViewModel,
    onOpenPlaying: () -> Unit,
) {
    val radio by vm.radio.collectAsState()

    PhonoScreenShell(
        title = "NTS Radio",
        hideBackButton = true,
        rightIcon = Icons.Default.GraphicEq,
        onRightIconClick = onOpenPlaying,
        rightIconVisible = radio.isActive,
        horizontalPadding = legacyNToGridDp(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        radio.error?.let { err ->
            LightText(
                text = err,
                variant = LightTextVariant.Detail,
                color = PhonoSemanticColors.Error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = legacyNToGridDp(8)),
            )
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = legacyNToGridDp(20)),
        ) {
            CustomScrollView {
                item(key = "live-header") {
                    SectionCaption("Live")
                }
                items(NtsStreams.LIVE, key = { it.id }) { stream ->
                    StreamRow(vm, radio, stream, onOpenPlaying)
                }
                item(key = "mixtape-header") {
                    SectionCaption("Mixtapes")
                }
                items(NtsStreams.MIXTAPES, key = { it.id }) { stream ->
                    StreamRow(vm, radio, stream, onOpenPlaying)
                }
            }
        }
    }
}

@Composable
private fun SectionCaption(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        color = PhonoSemanticColors.Placeholder,
        modifier = Modifier.padding(top = legacyNToGridDp(10), bottom = legacyNToGridDp(6)),
    )
}

@Composable
private fun StreamRow(
    vm: AppViewModel,
    radio: com.lightphone.spotify.radio.RadioUiState,
    stream: NtsStreams.Stream,
    onOpenPlaying: () -> Unit,
) {
    val current = radio.stream?.id == stream.id
    PhonoMediaListItem(
        primaryText = stream.title,
        // The show title is the useful line here, and only the playing stream has one — NTS gives no
        // per-station "what's on" without opening each stream.
        secondaryText = when {
            current && radio.buffering -> "Connecting…"
            current -> radio.nowPlayingTitle ?: "On air"
            else -> null
        },
        imageUrl = stream.artworkUrl,
        placeholderIcon = Icons.Default.Radio,
        showImage = true,
        onClick = {
            vm.playRadio(stream)
            onOpenPlaying()
        },
    )
}
