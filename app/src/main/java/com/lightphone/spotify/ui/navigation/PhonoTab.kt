package com.lightphone.spotify.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons

enum class PhonoTab(
    val route: String,
    val label: String,
    val icon: ImageVector? = null,
    val lightIcon: LightIconConfiguration? = null,
) {
    Liked(
        route = "liked",
        label = "Liked",
        icon = Icons.Filled.Favorite,
    ),
    Albums(
        route = "albums",
        label = "Albums",
        icon = Icons.Filled.Album,
    ),
    Playlists(
        route = "playlists",
        label = "Playlists",
        lightIcon = LightIcons.LIST,
    ),
    Search(
        route = "search",
        label = "Search",
        icon = Icons.Filled.Search,
    ),
    Radio(
        route = "radio",
        label = "NTS Radio",
        icon = Icons.Filled.Radio,
    ),
    Podcasts(
        route = "podcasts",
        label = "Podcasts",
        icon = Icons.Filled.Mic,
    ),
    Downloads(
        route = "downloads",
        label = "Downloads",
        lightIcon = LightIcons.DOWNLOAD_ARROW,
    ),
    Settings(
        route = "settings",
        label = "Settings",
        icon = Icons.Filled.MoreHoriz,
    ),
}

/**
 * The tab bar, in reaching order. The bar renders a **now-playing cover** between the
 * second and third entry, so four tabs is the full bar.
 *
 * Not here, deliberately:
 *
 *  - **Albums** — a switch inside [PhonoTab.Liked].
 *  - **Downloads** — opens as an overlay from Settings.
 *  - **Search** — the top-left button on every tab screen now.
 *  - **Settings** — the top-right "…" button on every tab screen now.
 *
 * The enum entries survive so the `when` over tabs stays exhaustive.
 */
fun phonoTabs(): List<PhonoTab> = listOf(
    PhonoTab.Playlists,
    PhonoTab.Liked,
    PhonoTab.Podcasts,
    PhonoTab.Radio,
)

/** Bar tabs a user can choose as the app's opening page. */
fun defaultPageChoices(): List<PhonoTab> = phonoTabs()

fun tabForRoute(route: String?): PhonoTab =
    PhonoTab.entries.firstOrNull { it.route == route && it in phonoTabs() } ?: PhonoTab.Playlists

val DefaultPhonoTabs = phonoTabs()
