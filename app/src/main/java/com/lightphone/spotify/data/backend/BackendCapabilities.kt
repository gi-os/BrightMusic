package com.lightphone.spotify.data.backend

/**
 * Soft feature gates derived from the active [BackendChoice].
 *
 * LightPhono is Spotify-only, so there is one set of capabilities today. The type is kept
 * rather than inlined because the screens already branch on it, and keeping the seam makes
 * merges from upstream phono (which still supports TIDAL) tractable.
 */
data class BackendCapabilities(
    /** Offline pin downloads (Downloads tab, album/playlist headers, hold menus). */
    val downloads: Boolean,
    /** Spotify 96/160/320 streaming quality ladder. */
    val spotifyStreamingQuality: Boolean,
) {
    companion object {
        fun forChoice(choice: BackendChoice): BackendCapabilities = when (choice) {
            BackendChoice.SPOTIFY -> BackendCapabilities(
                downloads = true,
                spotifyStreamingQuality = true,
            )
        }
    }
}

enum class CollectionKind(val path: String) {
    Album("album"),
    Playlist("playlist"),
}

/**
 * Canonical collection URI for the active backend. Prefer [existing] when non-blank.
 */
fun collectionUri(
    choice: BackendChoice,
    kind: CollectionKind,
    id: String,
    existing: String = "",
): String {
    if (existing.isNotBlank()) return existing
    val scheme = when (choice) {
        BackendChoice.SPOTIFY -> "spotify"
    }
    return "$scheme:${kind.path}:$id"
}
