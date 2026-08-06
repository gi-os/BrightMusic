package com.lightphone.spotify.ui.navigation

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gios.light.common.hw.WheelGate
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.ContextMenuHost
import com.lightphone.spotify.ui.components.PhonoTabBar
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.consumeScrimTouches
import com.lightphone.spotify.ui.phono.leftEdgeSwipeBack
import com.lightphone.spotify.ui.screens.AlbumDetailScreen
import com.lightphone.spotify.ui.screens.AlbumsScreen
import com.lightphone.spotify.ui.screens.ArtistDetailScreen
import com.lightphone.spotify.ui.screens.CreatePlaylistScreen
import com.lightphone.spotify.ui.screens.DevicesScreen
import com.lightphone.spotify.ui.screens.DownloadCollectionDetailScreen
import com.lightphone.spotify.ui.screens.DownloadsScreen
import com.lightphone.spotify.ui.screens.LikedScreen
import com.lightphone.spotify.ui.screens.PlayingScreen
import com.lightphone.spotify.ui.screens.PlaylistDetailScreen
import com.lightphone.spotify.ui.screens.PlaylistPickerScreen
import com.lightphone.spotify.ui.screens.PlaylistsScreen
import com.lightphone.spotify.ui.screens.PodcastShowScreen
import com.lightphone.spotify.ui.screens.PodcastsScreen
import com.lightphone.spotify.ui.screens.QueueScreen
import com.lightphone.spotify.ui.screens.RadioScreen
import com.lightphone.spotify.ui.screens.RadioSearchInputScreen
import com.lightphone.spotify.ui.screens.RadioSearchScreen
import com.lightphone.spotify.ui.screens.SavedEpisodesScreen
import com.lightphone.spotify.ui.screens.SearchInputScreen
import com.lightphone.spotify.ui.screens.SearchResultsScreen
import com.lightphone.spotify.ui.screens.SearchScreen
import com.lightphone.spotify.ui.screens.SettingsScreen
import com.lightphone.spotify.ui.screens.SleepTimerScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal const val OverlayRoot = "overlay_root"

private data class PhonoShellPlaybackState(
    val loggedIn: Boolean,
    val sessionExpired: Boolean,
    val reconnecting: Boolean,
    val networkOnline: Boolean,
    val statusMessage: String?,
    val error: String?,
)

@Composable
fun PhonoShell(
    vm: AppViewModel,
    shellVm: PhonoShellViewModel = viewModel(),
) {
    val overlayNavController = rememberNavController()
    val overlayNav = rememberOverlayNavigator(overlayNavController)
    // visibleEntries includes the exiting route for the exit frame; currentBackStackEntry does not.
    // Gating on current only collapses NavHost to 0×0 while the detail (and scrollbar) still draw.
    val visibleOverlayEntries by overlayNavController.visibleEntries.collectAsState()
    val shellPlayback by remember(vm) {
        vm.playback.map { p ->
            PhonoShellPlaybackState(
                loggedIn = p.loggedIn,
                sessionExpired = p.sessionExpired,
                reconnecting = p.reconnecting,
                networkOnline = p.networkOnline,
                statusMessage = p.statusMessage,
                error = p.error,
            )
        }.distinctUntilChanged()
    }.collectAsState(
        initial = PhonoShellPlaybackState(
            loggedIn = vm.playback.value.loggedIn,
            sessionExpired = vm.playback.value.sessionExpired,
            reconnecting = vm.playback.value.reconnecting,
            networkOnline = vm.playback.value.networkOnline,
            statusMessage = vm.playback.value.statusMessage,
            error = vm.playback.value.error,
        ),
    )
    val currentTab by shellVm.currentTab.collectAsState()
    val tabs = remember { phonoTabs() }

    LaunchedEffect(shellPlayback.loggedIn) {
        if (!shellPlayback.loggedIn) {
            overlayNav.popToRoot()
        }
    }

    LaunchedEffect(tabs, currentTab) {
        if (currentTab !in tabs) shellVm.selectTab(PhonoTab.Playlists)
    }

    // Opening the app with no connection: go straight to Downloads, the only thing that can play.
    // Once per process, and only if there is something downloaded — otherwise this would drop the
    // user on an empty screen they then have to back out of. Not keyed on `networkOnline`, because
    // losing signal mid-session should not yank you out of what you were doing; this is about what
    // the app opens on. `hasDownloads` is in the key so the check re-runs when the download list
    // arrives, which is usually a frame or two after the first composition.
    val hasDownloads by vm.hasDownloadedContent.collectAsState()
    LaunchedEffect(shellPlayback.loggedIn, hasDownloads) {
        if (offlineDownloadsOpened) return@LaunchedEffect
        if (!shellPlayback.loggedIn || shellPlayback.networkOnline || !hasDownloads) return@LaunchedEffect
        offlineDownloadsOpened = true
        overlayNav.navigate(OverlayDestination.Downloads)
    }

    val showOverlayLayer = visibleOverlayEntries.any { entry ->
        val route = entry.destination.route?.substringBefore('?')
        route != null && route != OverlayRoot
    }
    val contextMenu by vm.contextMenu.collectAsState()
    val modalOpen = contextMenu.target != null ||
        contextMenu.showCopied ||
        contextMenu.deleteConfirm != null ||
        contextMenu.removeDownloadConfirm != null
    val swipeBackEnabled = showOverlayLayer && !modalOpen
    val navbarStatusMessage = when {
        // Suppressed only when there is a banner saying the same thing; see sessionExpiredNow below.
        shellPlayback.sessionExpired && shellPlayback.networkOnline -> null
        shellPlayback.reconnecting -> "Reconnecting…"
        !shellPlayback.networkOnline -> "Device offline"
        else -> null
    }
    // Offline, "Device offline" is the useful message; a stale session-expired flag from before the
    // connection dropped must not outrank it.
    val sessionExpiredNow = shellPlayback.sessionExpired && shellPlayback.networkOnline
    val showSessionBanner = sessionExpiredNow && shellPlayback.statusMessage != null
    val colors = LightThemeTokens.colors

    BackHandler(enabled = modalOpen || showOverlayLayer) {
        when {
            contextMenu.showCopied -> vm.dismissCopiedOverlay()
            contextMenu.removeDownloadConfirm != null -> vm.cancelRemoveDownload()
            contextMenu.deleteConfirm != null -> vm.cancelDeletePlaylist()
            contextMenu.target != null -> vm.dismissContextMenu()
            showOverlayLayer -> overlayNavController.popBackStack()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        if (showSessionBanner) {
            shellPlayback.statusMessage?.let { msg ->
                LightText(
                    text = msg,
                    variant = LightTextVariant.Detail,
                    color = PhonoSemanticColors.Warning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = legacyNToGridDp(12), vertical = legacyNToGridDp(4)),
                )
            }
        }
        shellPlayback.error?.let { err ->
            if (!showSessionBanner) {
                LightText(
                    text = err,
                    variant = LightTextVariant.Detail,
                    color = PhonoSemanticColors.Error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = legacyNToGridDp(12), vertical = legacyNToGridDp(4)),
                )
            }
        }

        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    // The tab stays composed while an overlay or a context menu is over it, so
                    // without this every layer would answer the same wheel notch at once.
                    WheelGate(active = !showOverlayLayer && !modalOpen) {
                        when (currentTab) {
                            PhonoTab.Liked -> LikedScreen(
                                vm = vm,
                                onOpenPlaying = { overlayNav.navigate(OverlayDestination.Playing) },
                                onPlayTrack = { index ->
                                    vm.playLikedFrom(index)
                                    overlayNav.navigate(OverlayDestination.Playing)
                                },
                                onOpenAlbum = { id, name ->
                                    overlayNav.navigate(OverlayDestination.Album(id, name))
                                },
                            )
                            // Not in phonoTabs() any more — it is a switch inside Liked. The branch
                            // stays so the `when` is exhaustive over the enum.
                            PhonoTab.Albums -> AlbumsScreen(
                                vm = vm,
                                onOpenPlaying = { overlayNav.navigate(OverlayDestination.Playing) },
                                onOpenAlbum = { id, name ->
                                    overlayNav.navigate(OverlayDestination.Album(id, name))
                                },
                            )
                            PhonoTab.Podcasts -> PodcastsScreen(
                                vm = vm,
                                onOpenPlaying = { overlayNav.navigate(OverlayDestination.Playing) },
                                onOpenShow = { id, name ->
                                    overlayNav.navigate(OverlayDestination.PodcastShow(id, name))
                                },
                                onOpenSavedEpisodes = {
                                    overlayNav.navigate(OverlayDestination.SavedEpisodes)
                                },
                            )
                            PhonoTab.Radio -> RadioScreen(
                                vm = vm,
                                onOpenPlaying = { overlayNav.navigate(OverlayDestination.Playing) },
                                onOpenSearch = {
                                    overlayNav.navigate(OverlayDestination.RadioSearch)
                                },
                            )
                            PhonoTab.Playlists -> PlaylistsScreen(
                                vm = vm,
                                onOpenPlaying = { overlayNav.navigate(OverlayDestination.Playing) },
                                onOpenPlaylist = { id, name ->
                                    overlayNav.navigate(OverlayDestination.Playlist(id, name))
                                },
                                onCreatePlaylist = {
                                    vm.resetCreatePlaylistState()
                                    overlayNav.navigate(OverlayDestination.CreatePlaylist)
                                },
                            )
                            PhonoTab.Search -> SearchScreen(
                                vm = vm,
                                onOpenEditor = { query ->
                                    overlayNav.navigate(OverlayDestination.SearchInput(query))
                                },
                            )
                            // PhonoTab.Downloads is no longer in phonoTabs() — it opens as an overlay
                            // from Settings. The branch stays so the `when` is exhaustive over the enum.
                            PhonoTab.Downloads -> DownloadsScreen(
                                vm = vm,
                                onOpenPlaying = { overlayNav.navigate(OverlayDestination.Playing) },
                                onOpenCollection = { uri, title ->
                                    overlayNav.navigate(
                                        OverlayDestination.DownloadCollection(uri, title),
                                    )
                                },
                            )
                            PhonoTab.Settings -> {
                                val activity = LocalContext.current as? ComponentActivity
                                SettingsScreen(
                                    vm = vm,
                                    onLogout = {
                                        vm.logout {
                                            // Drop retained ViewModels so the next backend pick
                                            // builds a fresh AppViewModel with the right choice.
                                            activity?.viewModelStore?.clear()
                                            activity?.recreate()
                                        }
                                    },
                                    onOpenDownloads = {
                                        overlayNav.navigate(OverlayDestination.Downloads)
                                    },
                                )
                            }
                        }
                    }
                }

                PhonoTabBar(
                    tabs = tabs,
                    currentTab = currentTab,
                    onTabSelected = shellVm::selectTab,
                    statusMessage = navbarStatusMessage,
                )
            }

            Box(Modifier.fillMaxSize().zIndex(1f)) {
                if (showOverlayLayer) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(colors.background)
                            .consumeScrimTouches(),
                    )
                }
                WheelGate(active = !modalOpen) {
                    // size(0.dp) when idle so touches reach tabs; stay fillMaxSize while any
                    // non-root entry is still visible (including the exit frame) so scrollbars
                    // keep TopEnd.
                    NavHost(
                        navController = overlayNavController,
                        startDestination = OverlayRoot,
                        modifier = if (showOverlayLayer) {
                            Modifier
                                .fillMaxSize()
                                .leftEdgeSwipeBack(
                                    enabled = swipeBackEnabled,
                                    edgeWidth = 1.5f.gridUnitsAsDp(),
                                    distanceThreshold = 3f.gridUnitsAsDp(),
                                    onBack = { overlayNavController.popBackStack() },
                                )
                        } else {
                            Modifier.size(0.dp)
                        },
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                        sizeTransform = { null },
                    ) {
                        composable(OverlayRoot) {
                            Box(Modifier.fillMaxSize())
                        }
                        overlayDestinations(
                            vm = vm,
                            overlayNav = overlayNav,
                            overlayNavController = overlayNavController,
                        )
                    }
                }
            }

            ContextMenuHost(
                vm = vm,
                onNavigateToPlaylistPicker = { uri ->
                    vm.loadPlaylistPicker(uri)
                    overlayNav.navigate(OverlayDestination.PlaylistPicker(uri))
                },
            )
        }
    }
}

private fun NavGraphBuilder.overlayDestinations(
    vm: AppViewModel,
    overlayNav: OverlayNavigator,
    overlayNavController: NavHostController,
) {
    composable(Routes.Playing) {
        PlayingScreen(
            vm = vm,
            onBack = { overlayNavController.popBackStack() },
            onOpenAlbum = { albumId ->
                overlayNav.navigate(OverlayDestination.Album(albumId))
            },
            onOpenQueue = { overlayNav.navigate(OverlayDestination.Queue) },
            onOpenDevices = { overlayNav.navigate(OverlayDestination.Devices) },
            onOpenSleepTimer = { overlayNav.navigate(OverlayDestination.SleepTimer) },
            onAddToPlaylist = { uri ->
                vm.loadPlaylistPicker(uri)
                overlayNav.navigate(OverlayDestination.PlaylistPicker(uri))
            },
        )
    }
    composable(Routes.SleepTimer) {
        SleepTimerScreen(
            vm = vm,
            onBack = { overlayNavController.popBackStack() },
        )
    }
    composable(Routes.Queue) {
        QueueScreen(
            vm = vm,
            onBack = { overlayNavController.popBackStack() },
        )
    }
    composable(
        route = Routes.PodcastShow,
        arguments = listOf(
            navArgument("showId") { type = NavType.StringType },
            navArgument("title") {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) { entry ->
        PodcastShowScreen(
            vm = vm,
            showId = entry.arguments?.getString("showId").orEmpty(),
            fallbackTitle = Uri.decode(entry.arguments?.getString("title").orEmpty()),
            onBack = { overlayNavController.popBackStack() },
            onOpenPlaying = { overlayNav.navigate(OverlayDestination.Playing) },
        )
    }
    composable(Routes.Downloads) {
        DownloadsScreen(
            vm = vm,
            onOpenPlaying = { overlayNav.navigate(OverlayDestination.Playing) },
            onOpenCollection = { uri, title ->
                overlayNav.navigate(OverlayDestination.DownloadCollection(uri, title))
            },
            // Opened from Settings, so it needs a visible way out. Passing onBack also moves Edit
            // to the secondary-right slot inside the screen, since the shell gives back priority
            // over leftIcon.
            onBack = { overlayNavController.popBackStack() },
        )
    }
    composable(Routes.Devices) {
        DevicesScreen(
            vm = vm,
            onBack = { overlayNavController.popBackStack() },
            // Sending the user back through Step 2 is the only fix for a token that
            // predates the player scopes.
            onReauthorize = {
                overlayNavController.popBackStack()
                vm.beginWebApiReauthorize()
            },
        )
    }
    composable(
        route = Routes.SearchInput,
        arguments = listOf(
            navArgument("query") {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) { entry ->
        val initialQuery = entry.arguments?.getString("query").orEmpty()
        SearchInputScreen(
            initialQuery = initialQuery,
            onSubmit = { query ->
                vm.updateSearchQuery(query)
                overlayNavController.popBackStack()
                overlayNav.navigate(OverlayDestination.SearchResults(query))
            },
            onBack = { overlayNavController.popBackStack() },
        )
    }
    composable(
        route = Routes.SearchResults,
        arguments = listOf(navArgument("query") { type = NavType.StringType }),
    ) { entry ->
        val query = entry.arguments?.getString("query").orEmpty()
        SearchResultsScreen(
            vm = vm,
            query = query,
            onBack = { overlayNavController.popBackStack() },
            onOpenAlbum = { id, name ->
                overlayNav.navigate(OverlayDestination.Album(id, name))
            },
            onOpenArtist = { id -> overlayNav.navigate(OverlayDestination.Artist(id)) },
            onPlayTrack = { track ->
                vm.playSearchTrack(track)
                overlayNav.navigate(OverlayDestination.Playing)
            },
            onOpenPlaylist = { id, name ->
                overlayNav.navigate(OverlayDestination.Playlist(id, name))
            },
            onOpenShow = { id, name ->
                overlayNav.navigate(OverlayDestination.PodcastShow(id, name))
            },
        )
    }
    composable(
        route = Routes.Album,
        arguments = listOf(
            navArgument("albumId") { type = NavType.StringType },
            navArgument("title") { type = NavType.StringType; defaultValue = "" },
        ),
    ) { entry ->
        val albumId = entry.arguments?.getString("albumId").orEmpty()
        val title = entry.arguments?.getString("title").orEmpty()
        AlbumDetailScreen(
            vm = vm,
            albumId = albumId,
            fallbackTitle = title.ifBlank { "Album" },
            onBack = { overlayNavController.popBackStack() },
            onOpenArtist = { id -> overlayNav.navigate(OverlayDestination.Artist(id)) },
            onPlayTrack = { index ->
                vm.playAlbumFrom(albumId, index)
                overlayNav.navigate(OverlayDestination.Playing)
            },
        )
    }
    composable(
        route = Routes.Playlist,
        arguments = listOf(
            navArgument("playlistId") { type = NavType.StringType },
            navArgument("title") { type = NavType.StringType; defaultValue = "" },
        ),
    ) { entry ->
        val playlistId = entry.arguments?.getString("playlistId").orEmpty()
        val title = entry.arguments?.getString("title").orEmpty()
        PlaylistDetailScreen(
            vm = vm,
            playlistId = playlistId,
            fallbackTitle = title.ifBlank { "Playlist" },
            onBack = { overlayNavController.popBackStack() },
            onPlayTrack = { index ->
                vm.playPlaylistFrom(playlistId, index)
                overlayNav.navigate(OverlayDestination.Playing)
            },
        )
    }
    composable(Routes.SavedEpisodes) {
        SavedEpisodesScreen(
            vm = vm,
            onOpenPlaying = { overlayNav.navigate(OverlayDestination.Playing) },
            onBack = { overlayNavController.popBackStack() },
        )
    }
    composable(Routes.RadioSearch) {
        RadioSearchScreen(
            vm = vm,
            onOpenPlaying = { overlayNav.navigate(OverlayDestination.Playing) },
            onOpenEditor = { query ->
                overlayNav.navigate(OverlayDestination.RadioSearchInput(query))
            },
            onBack = { overlayNavController.popBackStack() },
        )
    }
    composable(
        route = Routes.RadioSearchInput,
        arguments = listOf(
            navArgument("query") {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) { entry ->
        RadioSearchInputScreen(
            initialQuery = entry.arguments?.getString("query").orEmpty(),
            onSubmit = { query ->
                vm.searchRadioStations(query)
                // Pop the editor rather than pushing results: the results live on the screen that is
                // already underneath, so navigating forward would stack a second copy of it.
                overlayNavController.popBackStack()
            },
            onBack = { overlayNavController.popBackStack() },
        )
    }
    composable(Routes.CreatePlaylist) {
        CreatePlaylistScreen(
            vm = vm,
            onBack = { overlayNavController.popBackStack() },
            onCreated = { id, name ->
                overlayNavController.popBackStack()
                overlayNav.navigate(OverlayDestination.Playlist(id, name))
            },
        )
    }
    composable(
        route = Routes.PlaylistPicker,
        arguments = listOf(navArgument("trackUri") { type = NavType.StringType }),
    ) { entry ->
        val trackUri = entry.arguments?.getString("trackUri").orEmpty()
        PlaylistPickerScreen(
            vm = vm,
            trackUri = trackUri,
            onBack = { overlayNavController.popBackStack() },
            onCreatePlaylist = {
                vm.resetCreatePlaylistState()
                overlayNav.navigate(OverlayDestination.CreatePlaylist)
            },
            onAdded = { overlayNavController.popBackStack() },
        )
    }
    composable(
        route = Routes.Artist,
        arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
    ) { entry ->
        val artistId = entry.arguments?.getString("artistId").orEmpty()
        ArtistDetailScreen(
            vm = vm,
            artistId = artistId,
            onBack = { overlayNavController.popBackStack() },
            onOpenAlbum = { id, name ->
                overlayNav.navigate(OverlayDestination.Album(id, name))
            },
            onPlayTopTrack = { index ->
                vm.playArtistTopTrack(index)
                overlayNav.navigate(OverlayDestination.Playing)
            },
        )
    }
    composable(
        route = Routes.DownloadCollection,
        arguments = listOf(
            navArgument("collectionUri") { type = NavType.StringType },
            navArgument("title") {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) { entry ->
        val collectionUri = Uri.decode(entry.arguments?.getString("collectionUri").orEmpty())
        val title = Uri.decode(entry.arguments?.getString("title").orEmpty()).ifBlank { "Downloads" }
        DownloadCollectionDetailScreen(
            vm = vm,
            collectionUri = collectionUri,
            title = title,
            onBack = { overlayNavController.popBackStack() },
            onPlayTrack = { track ->
                vm.playTracks(listOf(track), 0, title)
                overlayNav.navigate(OverlayDestination.Playing)
            },
        )
    }
}

/**
 * Whether the offline jump to Downloads has already happened this process.
 *
 * Deliberately not `remember`ed and not in the ViewModel: a config change or a tab switch recreates
 * the composition, and either would re-trigger the jump and pull the user out of wherever they had
 * navigated to. Process-scoped is the correct lifetime — the point is "what the app opens on".
 */
private var offlineDownloadsOpened = false
