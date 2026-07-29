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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.lightphone.spotify.audio.AudioOutputs
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.PhonoMediaListItem
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.lightphone.spotify.ui.phono.PhonoTextButton
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant

/**
 * Audio output picker — the in-app replacement for the Bluetooth settings shortcut, which does not
 * resolve on LightOS.
 *
 * Two lists, deliberately separated, because Android permits very different things for each:
 *
 *  - **Output** — outputs that are live right now. Tapping one routes *this app's* audio to it via
 *    `AudioTrack.setPreferredDevice`, which works and takes effect immediately.
 *  - **Paired** — bonded headphones with no live connection. Shown so the screen answers "where did my
 *    headphones go", but greyed: there is no public API for an app to connect a Bluetooth device, and
 *    the permission that would allow it is signature-only, so it is not even an adb grant away.
 *
 * The distinction is stated in the UI rather than hidden, because a row that looks tappable and does
 * nothing is worse than a row that explains itself.
 */
@Composable
fun BluetoothScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var hasPermission by remember { mutableStateOf(AudioOutputs.hasBluetoothConnect(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        refreshKey++
    }

    // Recomputed on entry and on every refresh tap. Not a flow: connecting a device takes seconds of
    // human time, and a poll loop here would cost battery for a screen nobody stares at.
    val snapshot = remember(refreshKey, hasPermission) {
        AudioOutputs.snapshot(context, vm.preferredOutputId)
    }

    LaunchedEffect(Unit) {
        if (!hasPermission && AudioOutputs.bluetoothAdapterPresent(context)) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    PhonoScreenShell(
        title = "Output",
        hideBackButton = false,
        onBack = onBack,
        rightIcon = Icons.Default.Refresh,
        onRightIconClick = { refreshKey++ },
        horizontalPadding = legacyNToGridDp(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (!snapshot.bluetoothOn) {
            LightText(
                text = "Bluetooth is off. Turn it on from the phone's own settings — an app is not " +
                    "allowed to.",
                variant = LightTextVariant.Detail,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = legacyNToGridDp(8)),
            )
        }

        if (!hasPermission) {
            Column(Modifier.fillMaxWidth()) {
                LightText(
                    text = "Allow Bluetooth access to see your paired headphones by name.",
                    variant = LightTextVariant.Detail,
                )
                Spacer(Modifier.height(legacyNToGridDp(10)))
                PhonoTextButton(
                    text = "ALLOW",
                    onClick = { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                )
                Spacer(Modifier.height(legacyNToGridDp(14)))
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = legacyNToGridDp(20)),
        ) {
            if (snapshot.connected.isEmpty() && snapshot.pairedNotConnected.isEmpty()) {
                EmptyListMessage("No audio outputs found.")
            } else {
                CustomScrollView {
                    item(key = "auto") {
                        PhonoMediaListItem(
                            primaryText = "Automatic",
                            secondaryText = if (snapshot.preferredId == null) {
                                "Following the phone"
                            } else {
                                null
                            },
                            placeholderIcon = Icons.Default.PhoneAndroid,
                            showImage = true,
                            disabled = snapshot.preferredId == null,
                            onClick = {
                                vm.setPreferredOutput(null)
                                refreshKey++
                            },
                        )
                    }
                    items(snapshot.connected, key = { "out-${it.id}" }) { output ->
                        val isPreferred = output.id == snapshot.preferredId
                        val isRouted = output.id == snapshot.routedId
                        PhonoMediaListItem(
                            primaryText = output.name,
                            secondaryText = when {
                                isPreferred && isRouted -> "Playing here"
                                isPreferred -> "Selected"
                                isRouted -> "Currently playing"
                                else -> null
                            },
                            placeholderIcon = output.icon(),
                            showImage = true,
                            onClick = {
                                vm.setPreferredOutput(output.device)
                                refreshKey++
                            },
                        )
                    }
                    if (snapshot.pairedNotConnected.isNotEmpty()) {
                        item(key = "paired-header") {
                            LightText(
                                text = "Paired, not connected",
                                variant = LightTextVariant.Detail,
                                color = PhonoSemanticColors.Placeholder,
                                modifier = Modifier.padding(
                                    top = legacyNToGridDp(14),
                                    bottom = legacyNToGridDp(6),
                                ),
                            )
                        }
                        items(snapshot.pairedNotConnected, key = { "paired-$it" }) { name ->
                            PhonoMediaListItem(
                                primaryText = name,
                                // Honest about the platform limit rather than offering a dead tap.
                                secondaryText = "Connect from the headphones themselves",
                                placeholderIcon = Icons.Default.Bluetooth,
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

private fun AudioOutputs.Output.icon(): ImageVector = when (kind) {
    AudioOutputs.Kind.PHONE -> Icons.Default.PhoneAndroid
    AudioOutputs.Kind.BLUETOOTH -> Icons.Default.Bluetooth
    AudioOutputs.Kind.WIRED -> Icons.Default.Headset
    AudioOutputs.Kind.OTHER -> Icons.Default.Headset
}
