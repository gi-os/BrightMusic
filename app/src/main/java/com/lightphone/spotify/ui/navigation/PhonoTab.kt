package com.lightphone.spotify.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
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
        label = "Liked Songs",
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
 * The tab bar.
 *
 * Downloads is deliberately **not** here — it lives under the "…" tab instead. Six tabs already
 * crowd a 411dp-wide bar, and adding Radio made a seventh untenable; Downloads is also the one you
 * visit occasionally rather than daily, so it is the right one to demote.
 *
 * [includeDownloads] is kept in the signature because the capability gate still decides whether the
 * entry appears at all, just in Settings rather than here.
 */
fun phonoTabs(includeDownloads: Boolean): List<PhonoTab> = buildList {
    add(PhonoTab.Liked)
    add(PhonoTab.Albums)
    add(PhonoTab.Playlists)
    add(PhonoTab.Search)
    add(PhonoTab.Radio)
    add(PhonoTab.Settings)
}

val DefaultPhonoTabs = phonoTabs(includeDownloads = false)
