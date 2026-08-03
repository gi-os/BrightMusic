package com.lightphone.spotify.radio

/**
 * Anything the radio tab can play: an NTS channel, an NTS mixtape, or a station the user found in the
 * radio-browser.info directory.
 *
 * This replaced `NtsStreams.Stream`, whose `liveChannel`/`mixtapeAlias` pair encoded NTS's two
 * metadata sources as nullable fields on every station. Once arbitrary Icecast stations exist there is
 * a third source and a fourth (nothing at all), so the source became a sealed type: adding a provider
 * is a new [MetadataSource] and one branch in [RadioController.startMetadata], and the compiler finds
 * every place that has to care.
 *
 * `id` is what [RadioUiState] compares to decide which row is playing and what `withRadio` puts in the
 * synthetic `radio:` uri, so it has to be stable across process restarts — hence radio-browser's
 * station uuid rather than a list index.
 */
data class RadioStation(
    val id: String,
    val title: String,
    val url: String,
    /** Second line in a row: "89.1 FM · NYU", "MP3 128k", a country. Null renders nothing. */
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val metadata: MetadataSource = MetadataSource.None,
    /** Where the station came from, which is what decides whether it can be un-favourited. */
    val origin: Origin = Origin.Directory,
) {
    /** How to find out what is currently on air. */
    sealed interface MetadataSource {
        /** No title is available. The station name is all the player screen will show. */
        data object None : MetadataSource

        /** NTS's public `api/v2/live`, keyed by channel number. */
        data class NtsLive(val channel: Int) : MetadataSource

        /** The Firestore collection behind the NTS iOS app, keyed by mixtape alias. */
        data class NtsMixtape(val alias: String) : MetadataSource

        /**
         * An Icecast server's `status-json.xsl`, matched to this station's mount.
         *
         * `MediaPlayer` cannot read ICY in-band metadata — there is no API for `StreamTitle` at all,
         * which is why the title has to be fetched out of band even though the stream is carrying it.
         */
        data class IcecastStatus(val mount: String) : MetadataSource
    }

    enum class Origin {
        /** Part of the NTS catalogue built into the app; always present, never removable. */
        Nts,

        /** Found in the directory and saved by the user. */
        Directory,
    }

    /**
     * NTS relays are geo-load-balanced and effectively never drop mid-listen. A university Icecast box
     * is a single machine, so those get the reconnect path in [RadioController].
     */
    val shouldReconnect: Boolean get() = origin != Origin.Nts
}
