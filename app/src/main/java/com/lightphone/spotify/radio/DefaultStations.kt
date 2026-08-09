package com.lightphone.spotify.radio

import android.net.Uri

/**
 * The New York stations the Radio tab seeds itself with on first run, so the feature is useful before
 * anyone types anything.
 *
 * These are real [RadioBrowserApi] entries — the ids are the directory's own station uuids, not made-up
 * strings — which means a seeded station behaves exactly like one the user searched for: it can be
 * removed, and [RadioBrowserApi.byUuid] can refresh its stream URL if the directory corrects it later.
 *
 * Seeding happens once and is recorded in [RadioPreferences.seeded], so removing a station keeps it
 * removed rather than having it reappear on the next launch.
 */
object DefaultStations {

    val NEW_YORK: List<RadioStation> = listOf(
        station(
            uuid = "a44129d2-def2-4d18-89c5-03f10db95043",
            name = "WNYU",
            subtitle = "89.1 FM · NYU",
            url = "http://cinema.acs.its.nyu.edu:8000/wnyu128.mp3",
            // wnyu.org/apple-touch-icon.png 404s — checked. This is the logo Spinitron serves
            // for the station, which is also where its now-playing comes from.
            artwork = StationMetadata.WNYU_LOGO,
        ),
        station(
            uuid = "960985b2-0601-11e8-ae97-52543be04c81",
            name = "WNYC-FM",
            subtitle = "93.9 FM · Public Radio",
            url = "https://fm939.wnyc.org/wnycfm",
            artwork = "https://media.wnyc.org/i/300/300/c/80/1/wnyc_square_logo.png",
        ),
        station(
            uuid = "ea60c9ef-29e9-4f4a-ab30-21eb34769faf",
            name = "WKCR",
            subtitle = "89.9 FM · Columbia",
            url = "https://wkcr.streamguys1.com/live",
        ),
        station(
            uuid = "9618344a-0601-11e8-ae97-52543be04c81",
            name = "WFMU",
            subtitle = "91.1 FM · Freeform",
            url = "http://stream2.wfmu.org/freeform-128k",
        ),
        station(
            uuid = "3487079b-91b1-4fb8-b315-c4150e705b7a",
            name = "WBGO",
            subtitle = "88.3 FM · Jazz",
            url = "https://ais-sa8.cdnstream1.com/3629_128.mp3",
        ),
        station(
            uuid = "30928802-8f3b-4083-94ee-7cb3c6c41790",
            name = "WQXR",
            subtitle = "105.9 FM · Classical",
            // The directory's url_resolved carries a `nyprBrowserId` tracking parameter picked up by
            // whoever submitted it. Dropped here: it is one listener's browser id, and sending it from
            // every install would tag them all as the same person.
            url = "https://stream.wqxr.org/wqxr-web",
        ),
        station(
            uuid = "961a0840-0601-11e8-ae97-52543be04c81",
            name = "WBAI",
            subtitle = "99.5 FM · Pacifica",
            url = "http://stream.wbai.org:8000/wbai_128",
        ),
    )

    private fun station(
        uuid: String,
        name: String,
        subtitle: String,
        url: String,
        artwork: String? = null,
    ) = RadioStation(
        id = uuid,
        title = name,
        url = url,
        subtitle = subtitle,
        artworkUrl = artwork,
        metadata = RadioStation.MetadataSource.IcecastStatus(
            runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault(""),
        ),
        origin = RadioStation.Origin.Directory,
    )
}
