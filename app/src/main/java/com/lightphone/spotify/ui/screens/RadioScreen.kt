package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Radio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.lightphone.spotify.radio.NtsStreams
import com.lightphone.spotify.radio.RadioStation
import com.lightphone.spotify.radio.RadioUiState
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.PhonoMediaListItem
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * Radio: the user's saved stations, then the NTS catalogue.
 *
 * Uses the same rows and the same Now Playing screen as the Spotify side, which is the point — the
 * transport, the cover and the output picker all work identically once a stream is on, because radio
 * state is overlaid onto the shared playback state rather than given a player of its own.
 *
 * Saved stations come from [radio-browser.info](https://www.radio-browser.info) via the search overlay
 * and are seeded with New York on first run. NTS catalogue and metadata endpoints come from
 * [vandamd/nts-radio](https://github.com/vandamd/nts-radio).
 */
@Composable
fun RadioScreen(
    vm: AppViewModel,
    onOpenPlaying: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    val radio by vm.radio.collectAsState()
    val library by vm.radioLibrary.collectAsState()

    LaunchedEffect(Unit) { vm.loadRadioLibrary() }

    PhonoScreenShell(
        title = "Radio",
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

        LightTextField(
            label = "Find:",
            value = "",
            placeholder = "Search stations",
            onClick = onOpenSearch,
            underlineWidthFraction = 1f,
            modifier = Modifier.fillMaxWidth(),
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = legacyNToGridDp(20)),
        ) {
            CustomScrollView {
                if (library.stations.isNotEmpty()) {
                    item(key = "stations-header") { SectionCaption("Stations") }
                    items(library.stations, key = { it.id }) { station ->
                        StationRow(
                            vm = vm,
                            radio = radio,
                            station = station,
                            onOpenPlaying = onOpenPlaying,
                            // Hold to remove. Consistent with the long-press context menu on tracks,
                            // and it keeps a delete affordance off a row whose whole job is one tap.
                            onLongClick = { vm.removeRadioStation(station.id) },
                        )
                    }
                }
                item(key = "live-header") { SectionCaption("NTS Live") }
                items(NtsStreams.LIVE, key = { it.id }) { station ->
                    StationRow(vm, radio, station, onOpenPlaying)
                }
                item(key = "mixtape-header") { SectionCaption("NTS Mixtapes") }
                items(NtsStreams.MIXTAPES, key = { it.id }) { station ->
                    StationRow(vm, radio, station, onOpenPlaying)
                }
            }
        }
    }
}

/**
 * Directory search. Tapping a result saves it and plays it in one go — on a phone with no pointer,
 * making the user save a station and then hunt for it in another list to hear it is two steps too many.
 */
@Composable
fun RadioSearchScreen(
    vm: AppViewModel,
    onOpenPlaying: () -> Unit,
    onOpenEditor: (String) -> Unit,
    onBack: () -> Unit,
) {
    val search by vm.radioSearch.collectAsState()
    val library by vm.radioLibrary.collectAsState()
    val radio by vm.radio.collectAsState()

    PhonoScreenShell(
        title = "Search Stations",
        hideBackButton = false,
        onBack = onBack,
        rightIconVisible = false,
        horizontalPadding = legacyNToGridDp(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        LightTextField(
            label = "Station:",
            value = search.query,
            placeholder = "WNYU, jazz, BBC…",
            onClick = { onOpenEditor(search.query) },
            underlineWidthFraction = 1f,
            modifier = Modifier.fillMaxWidth(),
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = legacyNToGridDp(20)),
        ) {
            when {
                search.loading -> Hint("Searching…")
                search.results.isEmpty() && search.searched ->
                    Hint("Nothing found for \"${search.query}\".")

                search.results.isEmpty() -> Hint("Search radio-browser.info for any station.")
                else -> CustomScrollView {
                    items(search.results, key = { it.id }) { station ->
                        val saved = library.stations.any { it.id == station.id }
                        PhonoMediaListItem(
                            primaryText = station.title,
                            secondaryText = when {
                                radio.stream?.id == station.id && radio.buffering -> "Connecting…"
                                saved -> listOfNotNull("Saved", station.subtitle).joinToString(" · ")
                                else -> station.subtitle
                            },
                            imageUrl = station.artworkUrl,
                            placeholderIcon = Icons.Default.Radio,
                            showImage = true,
                            onClick = {
                                vm.addRadioStation(station)
                                vm.playRadio(station)
                                onOpenPlaying()
                            },
                            // Save without committing to listening, for building a list in one pass.
                            onLongClick = { vm.toggleRadioStation(station) },
                        )
                    }
                }
            }
        }
    }
}

/** Full-screen station query entry, backed by the system IME like Spotify search. */
@Composable
fun RadioSearchInputScreen(
    initialQuery: String,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
) {
    val textState = rememberTextFieldState(initialQuery)

    LightTextInputEditor(
        title = "Station",
        state = textState,
        onSubmit = { text ->
            val query = text.toString().trim()
            if (query.isNotBlank()) onSubmit(query)
        },
        onBack = onBack,
        submitIcon = LightIcons.SEARCH,
        submitLabel = "SEARCH",
        imeAction = ImeAction.Search,
        // Call signs are upper-case and genres are not; auto-capitalising either way is wrong.
        capitalization = KeyboardCapitalization.None,
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    )
}

@Composable
private fun Hint(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        color = PhonoSemanticColors.Placeholder,
        modifier = Modifier.padding(top = legacyNToGridDp(10)),
    )
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
private fun StationRow(
    vm: AppViewModel,
    radio: RadioUiState,
    station: RadioStation,
    onOpenPlaying: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val current = radio.stream?.id == station.id
    PhonoMediaListItem(
        primaryText = station.title,
        // Only the playing station has a now-playing line: neither NTS nor an Icecast server will say
        // what is on air without being asked per station, and asking for a whole list on every scroll
        // is a lot of requests for a subtitle.
        secondaryText = when {
            current && radio.buffering -> "Connecting…"
            current -> radio.nowPlayingTitle ?: "On air"
            else -> station.subtitle
        },
        imageUrl = station.artworkUrl,
        placeholderIcon = Icons.Default.Radio,
        showImage = true,
        onClick = {
            vm.playRadio(station)
            onOpenPlaying()
        },
        onLongClick = onLongClick,
    )
}
