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
 * The tab bar, in reaching order.
 *
 * Six entries. Seven fits arithmetically — 373dp of the 400dp available on a 411dp screen — but the
 * gaps collapse to 5dp, so six is where it stays comfortable.
 *
 * Two things are deliberately **not** here:
 *
 *  - **Albums**, which is now a switch inside [PhonoTab.Liked]. Songs and albums are the same idea,
 *    so they share a tab the way Playlists shares one between "By You" and "All".
 *  - **Downloads**, which lives under the "…" tab, being the one you visit occasionally.
 *
 * Both enum entries survive so the `when` over tabs stays exhaustive.
 *
 * The list is fixed now. It used to be built conditionally on the downloads capability; that gate now
 * lives on the Settings row that opens Downloads, so there is nothing left for this to vary on.
 */
fun phonoTabs(): List<PhonoTab> = listOf(
    PhonoTab.Playlists,
    PhonoTab.Liked,
    PhonoTab.Podcasts,
    PhonoTab.Radio,
    PhonoTab.Search,
    PhonoTab.Settings,
)

val DefaultPhonoTabs = phonoTabs()
