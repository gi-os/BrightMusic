package com.lightphone.spotify.ui.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightphone.spotify.data.backend.BackendChoice
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.light.LightPhonoTheme
import com.lightphone.spotify.ui.screens.EmptyListMessage
import com.lightphone.spotify.ui.screens.LoginScreen
import com.lightphone.spotify.ui.screens.WebApiSetupScreen
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private data class AppAuthState(
    val authInitialized: Boolean,
    val loggedIn: Boolean,
    val webApiReady: Boolean,
    val networkOnline: Boolean,
)

@Composable
fun SpotifyApp(
    vm: AppViewModel = viewModel(),
    onReturnToPicker: () -> Unit = {},
) {
    val auth by remember(vm) {
        vm.playback.map { p ->
            AppAuthState(
                authInitialized = p.authInitialized,
                loggedIn = p.loggedIn,
                webApiReady = p.webApiReady,
                networkOnline = p.networkOnline,
            )
        }.distinctUntilChanged()
    }.collectAsState(
        initial = AppAuthState(
            authInitialized = vm.playback.value.authInitialized,
            loggedIn = vm.playback.value.loggedIn,
            webApiReady = vm.playback.value.webApiReady,
            networkOnline = vm.playback.value.networkOnline,
        ),
    )
    val libraryBootstrapping by vm.libraryBootstrapping.collectAsState()
    // With no network the first sync cannot finish, so gating the whole app on it left the phone
    // stuck on "Loading your library…" — with downloaded music and NTS both sitting there working.
    // Offline goes straight to the shell and lets the individual screens show what they have.
    val online = auth.networkOnline
    val onLoginBack = {
        vm.logout(onReturnToPicker)
    }
    LightPhonoTheme {
        when {
            !auth.authInitialized -> Box(
                Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                EmptyListMessage("Loading…")
            }
            !auth.loggedIn -> LoginScreen(vm, onBack = onLoginBack)
            !auth.webApiReady -> WebApiSetupScreen(vm)
            else -> {
                LaunchedEffect(Unit) { vm.onLoggedIn() }
                // Cross-faded rather than swapped. The first sync finishes at an unpredictable
                // moment, so the switch always arrived as a jump-cut: one frame of "Loading your
                // library…", the next of a full tab screen. Both sides are the same background
                // colour, so a fade reads as the text giving way to the list rather than as two
                // screens changing places.
                //
                // Keyed on the boolean, so it plays once per transition. PhonoShell keeps its own
                // state across it — Crossfade holds both children only for the duration.
                Crossfade(
                    targetState = libraryBootstrapping && online,
                    animationSpec = tween(durationMillis = 450, easing = LinearOutSlowInEasing),
                    label = "library-bootstrap",
                ) { bootstrapping ->
                    if (bootstrapping) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(LightThemeTokens.colors.background),
                        ) {
                            EmptyListMessage("Loading your library…")
                        }
                    } else {
                        PhonoShell(vm)
                    }
                }
            }
        }
    }
}
