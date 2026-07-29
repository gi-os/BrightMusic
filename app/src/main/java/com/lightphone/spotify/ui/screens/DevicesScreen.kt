package com.lightphone.spotify.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.lightphone.spotify.audio.AudioOutputs
import com.lightphone.spotify.data.webapi.SpotifyDevice
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.PhonoMediaListItem
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.PinnedItems
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.lightphone.spotify.ui.phono.PhonoTextButton
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant

/**
 * Everywhere the audio can go, in one screen and in the order you actually reach for.
 *
 * **Bluetooth and local outputs come first**, because those are the everyday choice — plugging in
 * headphones is a daily act, casting to a speaker an occasional one. They used to live behind a
 * separate Output screen reached from the top bar, which meant two taps and a mental model for
 * something that should be at the top of the list.
 *
 * Then the three remaining groups, each meaning something different:
 *
 *  - **Spotify Connect** — devices registered to the account. Tapping hands playback over.
 *  - **On this network** — receivers found over mDNS that Spotify has not been told about. Listed to
 *    explain the gap; not yet targetable, see `ZeroconfDiscovery`.
 *
 * "This phone" is a synthetic row rather than a device id: LightPhono's Rust core does not run
 * librespot's connect feature, so Spotify never sees this phone as a target and returning to local
 * resumes the local engine instead of transferring.
 */
@Composable
fun DevicesScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onReauthorize: () -> Unit,
) {
    val state by vm.connect.collectAsState()
    val lanReceivers by vm.lanReceivers.collectAsState()
    val connecting by vm.connectingBluetooth.collectAsState()
    val bluetoothMessage by vm.bluetoothMessage.collectAsState()
    val context = LocalContext.current

    var refreshKey by remember { mutableIntStateOf(0) }
    var hasBtPermission by remember { mutableStateOf(AudioOutputs.hasBluetoothConnect(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasBtPermission = granted
        refreshKey++
    }

    // Recomputed on entry, on refresh, and when a connection attempt finishes so the newly connected
    // device appears. Not a flow: connecting takes seconds of human time and a poll loop would cost
    // battery for a screen nobody stares at.
    val outputs = remember(refreshKey, hasBtPermission, connecting) {
        AudioOutputs.snapshot(context, vm.preferredOutputId)
    }

    LaunchedEffect(Unit) {
        vm.refreshDevices()
        if (!hasBtPermission && AudioOutputs.bluetoothAdapterPresent(context)) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

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
        onRightIconClick = {
            vm.refreshDevices()
            refreshKey++
        },
        rightLoading = state.loading || state.transferring || connecting != null,
        horizontalPadding = legacyNToGridDp(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        bluetoothMessage?.let { msg ->
            LightText(
                text = msg,
                variant = LightTextVariant.Detail,
                color = PhonoSemanticColors.Warning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = legacyNToGridDp(8)),
            )
        }
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

        if (state.needsReauthorize) {
            // A token minted before this fork added the player scopes cannot control devices, and no
            // amount of retrying fixes it.
            Column(Modifier.fillMaxWidth()) {
                LightText(
                    text = "Device control needs two extra permissions that your saved Spotify " +
                        "authorization does not include yet.",
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
            CustomScrollView {
                outputSection(vm, outputs, connecting, refresh = { refreshKey++ })

                if (!hasBtPermission) {
                    item(key = "bt-permission") {
                        Column(Modifier.fillMaxWidth().padding(top = legacyNToGridDp(8))) {
                            LightText(
                                text = "Allow Bluetooth access to see paired headphones by name.",
                                variant = LightTextVariant.Detail,
                            )
                            Spacer(Modifier.height(legacyNToGridDp(8)))
                            PhonoTextButton(
                                text = "ALLOW",
                                onClick = {
                                    permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                },
                            )
                        }
                    }
                }

                connectSection(vm, state)
                lanSection(state, lanReceivers)
            }
        }
    }
}

/** Bluetooth and wired outputs — the everyday choice, so it leads. */
private fun LazyListScope.outputSection(
    vm: AppViewModel,
    outputs: AudioOutputs.Snapshot,
    connecting: String?,
    refresh: () -> Unit,
) {
    item(key = "output-header") { SectionCaption("Output") }

    item(key = "output-auto") {
        PhonoMediaListItem(
            primaryText = "Automatic",
            secondaryText = if (outputs.preferredId == null) "Following the phone" else null,
            placeholderIcon = Icons.Default.PhoneAndroid,
            showImage = true,
            disabled = outputs.preferredId == null,
            onClick = {
                vm.setPreferredOutput(null)
                refresh()
            },
        )
    }

    items(outputs.connected, key = { "out-${it.id}" }) { output ->
        val isPreferred = output.id == outputs.preferredId
        val isRouted = output.id == outputs.routedId
        PhonoMediaListItem(
            primaryText = output.name,
            // Anything in this list is connected, so say so. Previously a connected pair of
            // headphones that was neither selected nor currently routed showed no label at all,
            // which read as "not connected" — and routedId is null whenever nothing is playing,
            // so that was most of the time.
            secondaryText = when {
                isPreferred && isRouted -> "Playing here"
                isRouted -> "Playing here"
                isPreferred -> "Connected · selected"
                else -> "Connected"
            },
            placeholderIcon = output.icon(),
            showImage = true,
            onClick = {
                vm.setPreferredOutput(output.device)
                refresh()
            },
        )
    }

    PinnedItems.favouriteBluetoothName?.let { name ->
        item(key = "favourite-hint") {
            LightText(
                text = "Hold the cast button on the player to connect $name.",
                variant = LightTextVariant.Detail,
                color = PhonoSemanticColors.Placeholder,
                modifier = Modifier.padding(top = legacyNToGridDp(4), bottom = legacyNToGridDp(2)),
            )
        }
    }

    items(outputs.pairedNotConnected, key = { "paired-${it.address}" }) { paired ->
        val busy = connecting == paired.address
        val favourite = PinnedItems.favouriteBluetooth == paired.address
        PhonoMediaListItem(
            primaryText = paired.name,
            secondaryText = when {
                busy -> "Connecting…"
                favourite -> "Favourite · tap to connect"
                else -> "Tap to connect"
            },
            placeholderIcon = Icons.Default.Bluetooth,
            showImage = true,
            // Only greyed while another attempt is in flight. Android may refuse the connect, but
            // that is discovered by trying rather than assumed here.
            disabled = connecting != null && !busy,
            onClick = { vm.connectBluetooth(paired) },
            // Long-press sets or clears the favourite — the device a long-press on the player's cast
            // control connects to without opening this screen at all.
            onLongClick = {
                vm.setFavouriteBluetooth(if (favourite) null else paired)
            },
        )
    }
}

private fun LazyListScope.connectSection(vm: AppViewModel, state: com.lightphone.spotify.playback.connect.ConnectUiState) {
    item(key = "connect-header") { SectionCaption("Spotify Connect") }

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

    if (state.devices.isEmpty()) {
        item(key = "connect-empty") {
            LightText(
                text = "No other Spotify devices. Open Spotify on a speaker, computer or TV and it " +
                    "will show up here.",
                variant = LightTextVariant.Detail,
                color = PhonoSemanticColors.Placeholder,
                modifier = Modifier.padding(vertical = legacyNToGridDp(6)),
            )
        }
    }

    // Spotify returns a null id for untargetable devices, and two of those would collide on a bare
    // `it.id ?: it.name` — duplicate keys are a hard crash in Lazy layouts.
    items(state.devices, key = { it.id ?: "unidentified:${it.name}:${it.type}" }) { device ->
        PhonoMediaListItem(
            primaryText = device.name,
            secondaryText = device.secondaryLabel(
                isSelected = device.id == state.activeRemoteId,
                isExternal = device.id == state.externalActiveId,
            ),
            placeholderIcon = device.icon(),
            showImage = true,
            // Restricted devices reject remote control outright, so they are greyed rather than
            // hidden — otherwise the user wonders where their web player went.
            disabled = !device.isTransferable,
            onClick = { vm.castTo(device) },
        )
    }
}

private fun LazyListScope.lanSection(
    state: com.lightphone.spotify.playback.connect.ConnectUiState,
    lanReceivers: List<com.lightphone.spotify.playback.connect.ZeroconfDiscovery.Receiver>,
) {
    val unregistered = lanReceivers.filter { r ->
        r.confirmed && state.devices.none { it.id != null && it.id == r.deviceId }
    }
    if (unregistered.isEmpty()) return

    item(key = "lan-header") { SectionCaption("On this network") }
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

@Composable
private fun SectionCaption(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        color = PhonoSemanticColors.Placeholder,
        modifier = Modifier.padding(top = legacyNToGridDp(12), bottom = legacyNToGridDp(6)),
    )
}

private fun AudioOutputs.Output.icon(): ImageVector = when (kind) {
    AudioOutputs.Kind.PHONE -> Icons.Default.PhoneAndroid
    AudioOutputs.Kind.BLUETOOTH -> Icons.Default.Bluetooth
    AudioOutputs.Kind.WIRED -> Icons.Default.Headset
    AudioOutputs.Kind.OTHER -> Icons.Default.Headset
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
    // Everything else (speaker, castaudio, game_console, automobile, unknown) is something you point
    // audio at.
    else -> Icons.Default.Speaker
}
