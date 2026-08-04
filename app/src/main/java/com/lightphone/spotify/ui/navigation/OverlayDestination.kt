package com.lightphone.spotify.ui.navigation

import android.net.Uri

sealed class OverlayDestination {
    data object Playing : OverlayDestination()
    data object Queue : OverlayDestination()
    data object Devices : OverlayDestination()
    data object Downloads : OverlayDestination()
    data class PodcastShow(val id: String, val title: String = "") : OverlayDestination()
    data class SearchInput(val initialQuery: String = "") : OverlayDestination()
    data class SearchResults(val query: String) : OverlayDestination()
    data class Album(val id: String, val title: String = "") : OverlayDestination()
    data class Artist(val id: String) : OverlayDestination()
    data class Playlist(val id: String, val title: String = "") : OverlayDestination()
    data object CreatePlaylist : OverlayDestination()
    data object RadioSearch : OverlayDestination()
    data object SavedEpisodes : OverlayDestination()
    data class RadioSearchInput(val initialQuery: String = "") : OverlayDestination()
    data class PlaylistPicker(val trackUri: String) : OverlayDestination()
    data class DownloadCollection(val collectionUri: String, val title: String = "") : OverlayDestination()

    fun toRoute(): String = when (this) {
        Playing -> Routes.Playing
        Queue -> Routes.Queue
        Devices -> Routes.Devices
        Downloads -> Routes.Downloads
        is PodcastShow -> Routes.podcastShow(id, title)
        is SearchInput -> Routes.searchInput(initialQuery)
        is SearchResults -> Routes.searchResults(query)
        is Album -> Routes.album(id, title)
        is Artist -> Routes.artist(id)
        is Playlist -> Routes.playlist(id, title)
        CreatePlaylist -> Routes.CreatePlaylist
        RadioSearch -> Routes.RadioSearch
        SavedEpisodes -> Routes.SavedEpisodes
        is RadioSearchInput -> Routes.radioSearchInput(initialQuery)
        is PlaylistPicker -> Routes.playlistPicker(trackUri)
        is DownloadCollection -> Routes.downloadCollection(collectionUri, title)
    }

    companion object {
        fun fromRoute(route: String?, arguments: Map<String, String?> = emptyMap()): OverlayDestination? {
            val base = route?.substringBefore('?') ?: return null
            return when (base) {
                Routes.Playing -> Playing
                Routes.Queue -> Queue
                Routes.Devices -> Devices
                Routes.Downloads -> Downloads
                "podcast" -> PodcastShow(
                    id = arguments["showId"].orEmpty(),
                    title = Uri.decode(arguments["title"].orEmpty()),
                )
                "search_input" -> SearchInput(Uri.decode(arguments["query"].orEmpty()))
                "search_results" -> SearchResults(Uri.decode(arguments["query"].orEmpty()))
                "album" -> Album(
                    id = arguments["albumId"].orEmpty(),
                    title = Uri.decode(arguments["title"].orEmpty()),
                )
                "artist" -> Artist(id = arguments["artistId"].orEmpty())
                "playlist" -> Playlist(
                    id = arguments["playlistId"].orEmpty(),
                    title = Uri.decode(arguments["title"].orEmpty()),
                )
                Routes.CreatePlaylist -> CreatePlaylist
                Routes.RadioSearch -> RadioSearch
                Routes.SavedEpisodes -> SavedEpisodes
                "radio_search_input" -> RadioSearchInput(Uri.decode(arguments["query"].orEmpty()))
                "playlist_picker" -> PlaylistPicker(Uri.decode(arguments["trackUri"].orEmpty()))
                "download_collection" -> DownloadCollection(
                    collectionUri = Uri.decode(arguments["collectionUri"].orEmpty()),
                    title = Uri.decode(arguments["title"].orEmpty()),
                )
                else -> null
            }
        }
    }
}
