package com.lightphone.spotify.radio

/**
 * NTS Radio's two live channels and its mixtape stations.
 *
 * Stream URLs, mixtape aliases and cover URLs are taken from
 * **[vandamd/nts-radio](https://github.com/vandamd/nts-radio)**, an NTS client for the Light Phone III
 * by Vandam Dinh — the same author LightPhono's UI patterns descend from. That app is Expo/TypeScript
 * so none of its code is reusable here; what carries over is the catalogue and the two metadata
 * endpoints, which are the parts that took someone else the work to find.
 *
 * NTS serves plain Icecast-style streams rather than HLS, which is why [RadioPlayer] can use
 * `MediaPlayer` and this fork does not need ExoPlayer back after the TIDAL strip.
 */
object NtsStreams {

    private const val LIVE_BASE = "https://stream-relay-geo.ntslive.net"
    private const val MIXTAPE_BASE = "https://stream-mixtape-geo.ntslive.net"
    private const val ART = "https://media2.ntslive.co.uk/resize/800x800"

    val LIVE: List<RadioStation> = listOf(
        live(id = "live-1", title = "NTS 1", path = "stream", channel = 1),
        live(id = "live-2", title = "NTS 2", path = "stream2", channel = 2),
    )

    val MIXTAPES: List<RadioStation> = listOf(
        mixtape("poolside", "Poolside", "mixtape4", "cf5afb01-5a68-4fa0-a1c6-415b35d09ed6_1542931200.jpeg"),
        mixtape("slow-focus", "Slow Focus", "mixtape", "01f7cbe6-235f-4e33-8f2f-70152c91edf1_1542931200.jpeg"),
        mixtape("100-percent-hip-hop", "Low Key", "mixtape2", "b667c612-1ef6-4bfd-ae87-0cec0a19629d_1626307200.jpeg"),
        mixtape("memory-lane", "Memory Lane", "mixtape6", "f889399d-6277-46e2-9be9-840bbdd25cc5_1560470400.jpeg"),
        mixtape("4-to-the-floor", "4 To The Floor", "mixtape5", "c3bad52d-418b-4bf6-aff5-eea3b9ff1186_1542931200.jpeg"),
        mixtape("island-time", "Island Time", "mixtape21", "68541b02-903c-4caf-bba2-538d0b9bfedc_1590451200.jpeg"),
        mixtape("the-tube", "The Tube", "mixtape26", "f3657c6b-aa6b-4ad9-9c12-d9e9cbe7f68d_1626220800.jpeg"),
        mixtape("sheet-music", "Sheet Music", "mixtape35", "fe3dc346-2549-44cc-96c7-c3117056aa74_1668038400.jpeg"),
        mixtape("feelings", "Feelings", "mixtape27", "53026366-cf7c-4a57-af5c-c894d2375dc6_1626220800.jpeg"),
        mixtape("expansions", "Expansions", "mixtape3", "acc3ad65-05bd-495d-90cb-f5d81221464b_1542931200.jpeg"),
        mixtape("rap-house", "Rap House", "mixtape22", "916a2aa3-dcc5-4eb6-abea-b2f1914fb49a_1590451200.jpeg"),
        mixtape("labyrinth", "Labyrinth", "mixtape31", "4ce92a36-4942-4f35-9cc4-1d3e6c2be746_1638230400.jpeg"),
        mixtape("sweat", "Sweat", "mixtape24", "f0c77a19-670b-4979-ac6e-e93f6089b5bc_1622592000.png"),
        mixtape("otaku", "Otaku", "mixtape36", "0c693fdb-544c-4b85-9679-3268afa3a273_1668038400.jpeg"),
        mixtape("the-pit", "The Pit", "mixtape34", "9c9efb53-ce34-4a5e-997b-f8251be464a1_1668038400.jpeg"),
        mixtape("field-recordings", "Field Recordings", "mixtape23", "807d8db6-049d-4eeb-8515-57c02b251e73_1622592000.png"),
    )

    val ALL: List<RadioStation> = LIVE + MIXTAPES

    fun byId(id: String): RadioStation? = ALL.firstOrNull { it.id == id }

    private fun live(id: String, title: String, path: String, channel: Int) = RadioStation(
        id = id,
        title = title,
        subtitle = "NTS Live",
        url = "$LIVE_BASE/$path",
        metadata = RadioStation.MetadataSource.NtsLive(channel),
        origin = RadioStation.Origin.Nts,
    )

    private fun mixtape(alias: String, title: String, path: String, image: String) = RadioStation(
        id = "mixtape-$alias",
        title = title,
        subtitle = "NTS Mixtape",
        url = "$MIXTAPE_BASE/$path",
        artworkUrl = "$ART/$image",
        metadata = RadioStation.MetadataSource.NtsMixtape(alias),
        origin = RadioStation.Origin.Nts,
    )
}
