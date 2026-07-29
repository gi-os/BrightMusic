package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.lightphone.spotify.data.webapi.SpotifyDevice
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.PhonoMediaListItem
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.lightphone.spotify.ui.phono.PhonoTextButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant

/**
 * Spotify Connect device picker — "cast" from the Light Phone to a speaker, desktop, or
 * TV.
 *
 * The phone itself never appears in this list, because LightPhono's Rust core does not
 * run librespot's connect feature and so is invisible to Spotify as a target. "This
 * phone" is therefore a synthetic row rather than a device id, and choosing it resumes
 * the local engine instead of transferring anything.
 */
@Composable
fun DevicesScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onReauthorize: () -> Unit,
    onOpenOutput: () -> Unit = {},
) {
    val state by vm.connect.collectAsState()

    LaunchedEffect(Unit) { vm.refreshDevices() }

    val lanReceivers by vm.lanReceivers.collectAsState()

    // mDNS browsing needs the foreground, so it lives and dies with this screen rather than running
    // for the life of the app.
    DisposableEffect(Unit) {
        vm.startLanDiscovery()
        onDispose { vm.stopLanDiscovery() }
    }

    PhonoScreenShell(
        title = "Play on",
        hideBackButton = false,
        onBack = onBack,
        rightIcon = Icons.Default.Refresh,
        onRightIconClick = vm::refreshDevices,
        rightLoading = state.loading || state.transferring,
        // Opens the in-app output picker. Was a Settings.ACTION_BLUETOOTH_SETTINGS intent,
        // which does not resolve on LightOS. The glyph is the SDK's own.
        secondaryRightLightIcon = LightIcons.BLUETOOTH,
        onSecondaryRightIconClick = onOpenOutput,
        horizontalPadding = legacyNToGridDp(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.error != null) {
            LightText(
                text = state.error!!,
                variant = LightTextVariant.Detail,
                color = PhonoSemanticColors.Error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = legacyNToGridDp(8)),
            )
        }

        if (state.needsReauthorize) {
            // A token minted before this fork added the player scopes cannot control
            // devices, and no amount of retrying fixes it.
            Column(Modifier.fillMaxWidth()) {
                LightText(
                    text = "Device control needs two extra permissions that your saved " +
                        "Spotify authorization does not include yet.",
                    variant = LightTextVariant.Detail,
                )
                Spacer(Modifier.height(legacyNToGridDp(12)))
                PhonoTextButton(text = "RE-AUTHORIZE", onClick = onReauthorize)
            }
            return@PhonoScreenShell
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = legacyNToGridDp(20)),
        ) {
            // Gate on BOTH sources. Gating on the Web API list alone hid every LAN receiver
            // whenever the account had no registered devices — which is the exact situation the
            // mDNS browse exists to rescue.
            val nothingAtAll = state.devices.isEmpty() && lanReceivers.none { it.confirmed }
            when {
                nothingAtAll && state.loading -> EmptyListMessage("Looking for devices…")
                nothingAtAll -> EmptyListMessage(
                    "No devices found. Open Spotify on a speaker, computer, or TV and " +
                        "it will show up here.",
                )
                else -> CustomScrollView {
                    item(key = "this-phone") {
                        PhonoMediaListItem(
                            primaryText = "This phone",
                            secondaryText = if (state.isRemote) null else "Playing here",
                            placeholderIcon = Icons.Default.PhoneAndroid,
                            showImage = true,
                            disabled = !state.isRemote,
                            onClick = { if (state.isRemote) vm.returnToLocalPlayback() },
                        )
                    }
                    // Spotify returns a null id for untargetable devices, and two of
                    // those would collide on a bare `it.id ?: it.name` — duplicate keys
                    // are a hard crash in Lazy layouts, so fall back to name+type.
                    items(state.devices, key = { it.id ?: "unidentified:${it.name}:${it.type}" }) { device ->
                        PhonoMediaListItem(
                            primaryText = device.name,
                            secondaryText = device.secondaryLabel(
                                isSelected = device.id == state.activeRemoteId,
                                isExternal = device.id == state.externalActiveId,
                            ),
                            placeholderIcon = device.icon(),
                            showImage = true,
                            // Restricted devices reject remote control outright, so they
                            // are shown greyed rather than hidden — otherwise the user
                            // wonders where their web player went.
                            disabled = !device.isTransferable,
                            onClick = { vm.castTo(device) },
                        )
                    }
                    // Receivers seen on the network but not registered to the account. The Web API
                    // cannot target these yet — claiming them needs the ZeroConf addUser handshake —
                    // so they are shown to explain the gap rather than offered as a dead tap.
                    val unregistered = lanReceivers.filter { r ->
                        r.confirmed && state.devices.none { it.id != null && it.id == r.deviceId }
                    }
                    if (unregistered.isNotEmpty()) {
                        item(key = "lan-header") {
                            LightText(
                                text = "On this network",
                                variant = LightTextVariant.Detail,
                                color = PhonoSemanticColors.Placeholder,
                                modifier = Modifier.padding(
                                    top = legacyNToGridDp(14),
                                    bottom = legacyNToGridDp(6),
                                ),
                            )
                        }
                        items(unregistered, key = { "lan-${it.host}:${it.port}" }) { receiver ->
                            PhonoMediaListItem(
                                primaryText = listOfNotNull(receiver.brand, receiver.model)
                                    .joinToString(" ")
                                    .ifBlank { receiver.name },
                                secondaryText = "Found on Wi-Fi — start it once from Spotify to control it here",
                                placeholderIcon = Icons.Default.Speaker,
                                showImage = true,
                                disabled = true,
                                onClick = {},
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun SpotifyDevice.secondaryLabel(isSelected: Boolean, isExternal: Boolean): String? = when {
    // We handed playback here: the transport on this phone is driving it.
    isSelected -> "Playing from here"
    // Spotify says this device is playing, but we did not start it — usually a desktop. Said plainly
    // rather than dressed up as ours, because tapping it is what makes it ours.
    isExternal -> "Playing (tap to take over)"
    isRestricted -> "Cannot be controlled remotely"
    isPrivateSession -> "Private session"
    else -> type.replaceFirstChar { it.uppercase() }.takeIf { it.isNotBlank() }
}

private fun SpotifyDevice.icon(): ImageVector = when (type.lowercase()) {
    "computer" -> Icons.Default.Computer
    "smartphone", "tablet" -> Icons.Default.PhoneAndroid
    "tv", "castvideo", "stb", "avr" -> Icons.Default.Tv
    // Everything else (speaker, castaudio, game_console, automobile, unknown) is
    // something you point audio at.
    else -> Icons.Default.Speaker
}
