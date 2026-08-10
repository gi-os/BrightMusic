package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.lightphone.spotify.data.owntone.BridgeController
import com.lightphone.spotify.data.owntone.OwntoneOutput
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.PhonoMediaListItem
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant

@Composable
fun BridgeSpeakersScreen(
    bridge: BridgeController,
    onBack: () -> Unit,
) {
    val state by bridge.state.collectAsState()

    LaunchedEffect(Unit) { bridge.refreshSpeakers() }

    PhonoScreenShell(
        title = "Speakers",
        hideBackButton = false,
        onBack = onBack,
        rightIcon = Icons.Default.Refresh,
        onRightIconClick = { bridge.refreshSpeakers() },
        rightLoading = state.loading,
        horizontalPadding = legacyNToGridDp(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        state.error?.let { err ->
            LightText(
                text = err,
                variant = LightTextVariant.Detail,
                color = PhonoSemanticColors.Error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = legacyNToGridDp(8)),
            )
        }

        val speakers = state.speakers.filter { it.type.startsWith("AirPlay") }
        val nonAirPlay = state.speakers.filter { !it.type.startsWith("AirPlay") }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = legacyNToGridDp(20)),
        ) {
            CustomScrollView {
                item(key = "header") {
                    LightText(
                        text = "AirPlay",
                        variant = LightTextVariant.Detail,
                        color = PhonoSemanticColors.Placeholder,
                        modifier = Modifier.padding(
                            top = legacyNToGridDp(12),
                            bottom = legacyNToGridDp(6),
                        ),
                    )
                }

                if (speakers.isEmpty() && !state.loading) {
                    item(key = "empty") {
                        LightText(
                            text = "No AirPlay speakers found on the network.",
                            variant = LightTextVariant.Detail,
                            color = PhonoSemanticColors.Placeholder,
                            modifier = Modifier.padding(vertical = legacyNToGridDp(6)),
                        )
                    }
                }

                items(speakers, key = { it.id }) { speaker ->
                    PhonoMediaListItem(
                        primaryText = speaker.name,
                        secondaryText = if (speaker.selected) "Playing here" else "Tap to enable",
                        placeholderIcon = Icons.Default.Speaker,
                        showImage = true,
                        onClick = { bridge.toggleSpeaker(speaker.id, !speaker.selected) },
                    )
                }

                if (nonAirPlay.isNotEmpty()) {
                    item(key = "other-header") {
                        LightText(
                            text = "Other outputs",
                            variant = LightTextVariant.Detail,
                            color = PhonoSemanticColors.Placeholder,
                            modifier = Modifier.padding(
                                top = legacyNToGridDp(12),
                                bottom = legacyNToGridDp(6),
                            ),
                        )
                    }
                    items(nonAirPlay, key = { it.id }) { speaker ->
                        PhonoMediaListItem(
                            primaryText = speaker.name,
                            secondaryText = if (speaker.selected) "Playing here" else "Tap to enable",
                            placeholderIcon = Icons.Default.Speaker,
                            showImage = true,
                            onClick = { bridge.toggleSpeaker(speaker.id, !speaker.selected) },
                        )
                    }
                }
            }
        }
    }
}
