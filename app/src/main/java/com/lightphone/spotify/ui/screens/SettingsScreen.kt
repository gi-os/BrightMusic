package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import com.gios.light.common.hw.WheelScroll
import com.lightphone.spotify.ffi.NormalizationType
import com.lightphone.spotify.ffi.StreamingQuality
import com.lightphone.spotify.playback.SleepTimerVisibility
import com.lightphone.spotify.playback.lockscreen.LockScreenOverlaySettings
import com.lightphone.spotify.playback.download.AutoPinPlan
import com.lightphone.spotify.playback.download.LibraryAutoDownloadSettings
import com.lightphone.spotify.playback.TrackFade
import com.lightphone.spotify.playback.TrackFadeSettings
import com.lightphone.spotify.podcast.PodcastRetention
import com.lightphone.spotify.podcast.PodcastSettings
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.light.ArtworkSettings
import com.lightphone.spotify.ui.light.ArtworkTreatment
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

@Composable
fun SettingsScreen(
    vm: AppViewModel,
    onLogout: () -> Unit,
    onOpenDownloads: () -> Unit = {},
) {
    val settings by vm.settings.collectAsState()
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    val caps = vm.capabilities
    val scroll = rememberScrollState()

    // The only scroll surface in the app that is not a CustomScrollView. A confirmation replaces
    // the whole screen rather than covering it, so the list is not composed while one is up.
    WheelScroll(scroll, active = confirm == null)

    confirm?.let { request ->
        PhonoConfirmScreen(
            title = request.title,
            message = request.message,
            confirmText = request.confirmText,
            onConfirm = {
                request.onConfirm()
                confirm = null
            },
            onCancel = { confirm = null },
        )
        return
    }

    PhonoScreenShell(
        title = "Settings",
        hideBackButton = true,
        rightIconVisible = false,
        modifier = Modifier.fillMaxSize(),
    ) {
        LightScrollView(modifier = Modifier.weight(1f), scrollState = scroll) {
            Column(Modifier.fillMaxWidth()) {
                if (caps.downloads) {
                    // First thing in the menu: these are places you go, unlike everything below,
                    // which are settings you change.
                    SectionLabel("Library")
                    SettingsActionRow("Downloads", onClick = onOpenDownloads)

                    SectionLabel("Keep podcast episodes")
                    PodcastRetentionOptions(
                        selected = PodcastSettings.retention,
                        onSelect = vm::setPodcastRetention,
                    )

                    // Both off by default. An automatic downloader spends storage and data without
                    // being asked each time, so it is something you turn on, never something you
                    // discover has been running.
                    SectionLabel("Keep offline automatically")
                    SettingsToggleRow(
                        "Liked Songs",
                        LibraryAutoDownloadSettings.likedEnabled,
                        vm::setAutoDownloadLiked,
                    )
                    if (LibraryAutoDownloadSettings.likedEnabled) {
                        Spacer(Modifier.height(legacyNToGridDp(8)))
                        LikedLimitOptions(
                            selected = LibraryAutoDownloadSettings.likedLimit,
                            onSelect = vm::setAutoDownloadLikedLimit,
                        )
                    }
                    SettingsToggleRow(
                        "Daily Mixes",
                        LibraryAutoDownloadSettings.mixesEnabled,
                        vm::setAutoDownloadMixes,
                    )
                }

                SectionLabel("Appearance")
                SettingsToggleRow("Dark mode", settings.darkTheme, vm::setDarkTheme)

                SectionLabel("Album art")
                ArtworkTreatmentOptions(
                    selected = ArtworkSettings.treatment,
                    onSelect = vm::setArtworkTreatment,
                )
                if (ArtworkSettings.treatment != ArtworkTreatment.OFF) {
                    SettingsToggleRow(
                        "Cover on Now Playing",
                        ArtworkSettings.showNowPlayingArt,
                        vm::setShowNowPlayingArt,
                    )
                }

                SectionLabel("Playback")
                if (TrackFadeSettings.enabled) {
                    // Not a toggle while a fade is set: the fade needs the tight seam gapless
                    // gives it, so the player keeps it on regardless. A toggle that reads off
                    // while the engine runs it on would be a lie about what the phone is doing.
                    SettingsActionRow("Gapless playback — on, for the fade", selected = true) {}
                } else {
                    SettingsToggleRow("Gapless playback", settings.gaplessEnabled, vm::setGaplessEnabled)
                }
                SettingsToggleRow("Normalize volume", settings.normalizationEnabled, vm::setNormalizationEnabled)
                if (settings.normalizationEnabled) {
                    Spacer(Modifier.height(legacyNToGridDp(8)))
                    NormalizationOptions(settings.normalizationType, vm::setNormalizationType)
                }

                SectionLabel("Lock screen")
                if (vm.canDrawOverlays()) {
                    SettingsToggleRow(
                        "Playback controls",
                        LockScreenOverlaySettings.enabled,
                        vm::setLockScreenOverlayEnabled,
                    )
                    SettingsToggleRow(
                        "Song title along the bottom",
                        LockScreenOverlaySettings.titleEnabled,
                        vm::setLockScreenTitleEnabled,
                    )
                    SettingsNote(
                        "Controls over the LightOS lock screen when something is loaded, and nowhere " +
                            "else. Touch anywhere on the lock screen, or hold the controls, to put " +
                            "them away until the screen next comes on.",
                    )
                    if (!vm.canReadUsageStats()) {
                        SettingsNote(
                            "Nothing will appear yet: without usage access the app cannot tell which " +
                                "app is in front, and it will not guess — run: adb shell appops set " +
                                "com.lightphone.spotify GET_USAGE_STATS allow",
                        )
                    }
                    // Silent non-appearance is the one failure this feature can have, and nothing on
                    // the phone would otherwise show why.
                    SettingsNote(vm.lockScreenDiagnostics())
                } else {
                    SettingsNote(
                        "LightOS only draws its own player on the lock screen. This app can draw its " +
                            "controls there, but the phone has no screen for granting that — run: " +
                            "adb shell appops set com.lightphone.spotify SYSTEM_ALERT_WINDOW allow",
                    )
                }

                SectionLabel("Sleep timer")
                SettingsToggleRow(
                    "Show on Now Playing",
                    SleepTimerVisibility.enabled,
                    vm::setSleepTimerVisible,
                )
                SettingsNote(
                    "Off keeps the line off the player. A timer already counting down stays visible " +
                        "either way, so it can still be seen and cancelled.",
                )

                SectionLabel("Fade between tracks")
                TrackFadeOptions(TrackFadeSettings.seconds, vm::setTrackFadeSeconds)
                SettingsNote(
                    "Fades every track change, including on albums mixed to run together — " +
                        "which is why it is off by default. Not on radio, which has no track to " +
                        "change.",
                )

                SectionLabel("Audio quality")
                if (caps.spotifyStreamingQuality) {
                    StreamingQualityOptions(settings.streamingQuality, vm::setStreamingQuality)
                }
                if (caps.downloads) {
                    SectionLabel("Download quality")
                    if (caps.spotifyStreamingQuality) {
                        StreamingQualityOptions(settings.downloadQuality, vm::setDownloadQuality)
                    }
                }

                SectionLabel("Storage")
                SettingsActionRow("Clear Cache") {
                    confirm = ConfirmRequest(
                        title = "Clear Cache",
                        message = "Delete temporary streaming cache? Offline downloads and credentials are kept.",
                        confirmText = "Clear",
                        onConfirm = { vm.clearAudioCache() },
                    )
                }

                SectionLabel("Advanced")
                SettingsActionRow(
                    text = if (settings.showAdvanced) "Hide proxy settings" else "Show proxy settings",
                    onClick = vm::toggleAdvancedSettings,
                )
                if (settings.showAdvanced) {
                    Spacer(Modifier.height(legacyNToGridDp(12)))
                    ProxyField(settings.proxy, vm::setProxy)
                }

                SectionLabel("Account")
                SettingsActionRow("Logout") {
                    confirm = ConfirmRequest(
                        title = "Logout",
                        message = "Sign out of Spotify?",
                        confirmText = "Logout",
                        onConfirm = onLogout,
                    )
                }
                Spacer(Modifier.height(legacyNToGridDp(40)))
            }
        }
    }
}

private data class ConfirmRequest(
    val title: String,
    val message: String,
    val confirmText: String,
    val onConfirm: () -> Unit,
)

@Composable
private fun SectionLabel(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        color = PhonoSemanticColors.Placeholder,
        modifier = Modifier.padding(top = 1.5f.gridUnitsAsDp(), bottom = legacyNToGridDp(8)),
    )
}

@Composable
private fun SettingsActionRow(
    text: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        underline = selected,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = legacyNToGridDp(8)),
    )
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable { onCheckedChange(!checked) }
            .padding(vertical = legacyNToGridDp(8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightIcon(
            // LightIcons names are inverted vs the artwork (knob-left is labeled ON).
            icon = if (checked) LightIcons.TOGGLE_OFF else LightIcons.TOGGLE_ON,
            modifier = Modifier.padding(end = legacyNToGridDp(10)),
            contentDescription = null,
        )
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The length of the fade between tracks.
 *
 * A short list of even numbers rather than every second from 0 to 12: nobody can hear the
 * difference between a 5- and a 6-second fade, and seven rows fit a thumb where thirteen do not.
 */
@Composable
private fun TrackFadeOptions(selected: Int, onSelect: (Int) -> Unit) {
    TrackFade.CHOICES.forEach { seconds ->
        SettingsActionRow(
            text = TrackFade.label(seconds),
            selected = seconds == TrackFade.sanitize(selected),
            onClick = { onSelect(seconds) },
        )
    }
}

/** A sentence under a setting, for the one case where the interaction needs explaining. */
@Composable
private fun SettingsNote(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        color = PhonoSemanticColors.Placeholder,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = legacyNToGridDp(4), bottom = legacyNToGridDp(4)),
    )
}

@Composable
private fun StreamingQualityOptions(selected: StreamingQuality, onSelect: (StreamingQuality) -> Unit) {
    val options = listOf(
        StreamingQuality.LOW to "Low (96 kbps)",
        StreamingQuality.NORMAL to "Normal (160 kbps)",
        StreamingQuality.HIGH to "High (320 kbps)",
    )
    options.forEach { (quality, label) ->
        SettingsActionRow(text = label, selected = quality == selected, onClick = { onSelect(quality) })
    }
}

/**
 * How many downloaded episodes to keep per show. "Never delete" is last: it is the option that fills
 * the phone, so it should not be the one your thumb lands on.
 */
@Composable
private fun PodcastRetentionOptions(
    selected: PodcastRetention,
    onSelect: (PodcastRetention) -> Unit,
) {
    PodcastRetention.entries.forEach { retention ->
        SettingsActionRow(
            text = retention.label,
            selected = retention == selected,
            onClick = { onSelect(retention) },
        )
    }
}

/**
 * How many of the newest liked tracks to keep on the phone.
 *
 * A ceiling rather than "all of them", because a library built over years is routinely thousands of
 * tracks and tens of gigabytes — more than the phone has. The window rolls: liking something new
 * eventually drops the oldest one in the window, so the setting describes a size rather than a rate
 * of growth.
 */
@Composable
private fun LikedLimitOptions(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    AutoPinPlan.LIKED_LIMIT_CHOICES.filter { it > 0 }.forEach { limit ->
        SettingsActionRow(
            text = "Newest $limit",
            selected = limit == selected,
            onClick = { onSelect(limit) },
        )
    }
}

@Composable
private fun ArtworkTreatmentOptions(
    selected: ArtworkTreatment,
    onSelect: (ArtworkTreatment) -> Unit,
) {
    val options = listOf(
        ArtworkTreatment.COLOR to "Colour",
        ArtworkTreatment.DITHER to "Dithered",
        ArtworkTreatment.GREY to "Greyscale",
        ArtworkTreatment.OFF to "Off",
    )
    options.forEach { (treatment, label) ->
        SettingsActionRow(
            text = label,
            selected = treatment == selected,
            onClick = { onSelect(treatment) },
        )
    }
}

@Composable
private fun NormalizationOptions(selected: NormalizationType, onSelect: (NormalizationType) -> Unit) {
    val options = listOf(
        NormalizationType.AUTO to "Auto",
        NormalizationType.TRACK to "Track",
        NormalizationType.ALBUM to "Album",
    )
    options.forEach { (type, label) ->
        SettingsActionRow(text = label, selected = type == selected, onClick = { onSelect(type) })
    }
}

@Composable
private fun ProxyField(value: String, onChange: (String) -> Unit) {
    val colors = LightThemeTokens.colors
    val typography = LightThemeTokens.typography
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(bottom = legacyNToGridDp(6)),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = typography.copy.copy(color = colors.content),
                cursorBrush = SolidColor(colors.content),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        LightText(
                            text = "http://host:port",
                            variant = LightTextVariant.Copy,
                            color = PhonoSemanticColors.Placeholder,
                        )
                    }
                    inner()
                },
            )
        }
        Box(Modifier.fillMaxWidth().height(legacyNToGridDp(1)).background(colors.content))
    }
}

/** Full-screen destructive confirmation, ported from phono's confirm screen. */
@Composable
fun PhonoConfirmScreen(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    PhonoScreenShell(
        title = title,
        hideBackButton = false,
        onBack = onCancel,
        rightIconVisible = false,
        modifier = Modifier.fillMaxSize(),
    ) {
        LightText(
            text = message,
            variant = LightTextVariant.Paragraph,
            modifier = Modifier.padding(top = legacyNToGridDp(10)),
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LightText(
                text = confirmText.uppercase(),
                variant = LightTextVariant.Subtitle,
                align = TextAlign.Center,
                modifier = Modifier
                    .lightClickable(onClick = onConfirm)
                    .padding(vertical = legacyNToGridDp(15), horizontal = legacyNToGridDp(30)),
            )
        }
    }
}
