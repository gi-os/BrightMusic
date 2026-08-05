package com.lightphone.spotify.backup

import com.gios.light.common.sync.Contents
import com.gios.light.common.sync.FileStore
import com.gios.light.common.sync.LightSyncBackup

/**
 * What LightSync takes off this app, and — more usefully — what it deliberately does not.
 *
 * The rule applied here is: back up what only this phone knows. Everything Spotify can hand
 * back on the next sync, and everything that is a copy of audio already sitting on Spotify's
 * servers, is left where it is.
 *
 * **Backed up**
 *
 *  - *Library* — `phono_library.db` and the pins on the home screen. The database is liked
 *    tracks, saved albums, playlists, the album/playlist detail cache and the sync bookmarks
 *    that stop a cold start re-reading the whole library. Spotify owns most of this, but a
 *    restore that starts from an empty database spends its first several minutes paging the
 *    whole account back in over a phone radio, and the detail cache is never re-fetched until
 *    you happen to open each album again.
 *  - *Radio and podcasts* — the stations list and the podcast preferences. This is the
 *    expensive one. A station added from radio-browser.info exists nowhere else, and podcast
 *    subscriptions, retention settings and per-episode resume points are this app's own
 *    invention: Spotify has no record of any of it. Losing this is the only loss here that
 *    cannot be undone by waiting.
 *  - *Play history* — two years of one-file-per-day TSVs under `play-history`. Written only here,
 *    read by LightNotebook's journal, reconstructible from nothing.
 *  - *Settings* — theme, artwork treatment, backend choice, Connect speaker aliases, the
 *    auto-download rules and the resume point. Cheap to recreate by hand, which is why it is
 *    its own store rather than mixed into the library one; restoring it is a convenience, not
 *    a rescue.
 *
 * **Deliberately left out**
 *
 *  - `phono_web_api_auth` — an `EncryptedSharedPreferences` file whose master key lives in the
 *    AndroidKeyStore and cannot leave the device. The XML would restore, and then every read
 *    of it would throw, because the key that decrypts it no longer exists. Worse than useless:
 *    it turns a re-login into a crash. Sign in to the Web API again after a restore.
 *  - `spotify-cache/` — librespot's streaming cache and, inside it, `creds/credentials.json`.
 *    The cache is hundreds of megabytes of re-fetchable audio. The credential is small, but
 *    restoring it alone would leave the app half signed in: playback authenticated, metadata
 *    not, because the Web API side cannot come back (above). One login screen after a restore
 *    is clearer than two states that disagree.
 *  - `spotify-downloads/` — pinned offline audio. Large, and Spotify will hand it back. The
 *    download rows *are* in the restored database, pointing at files that are not there yet;
 *    they re-download on demand rather than failing.
 *  - `phono_offline_hygiene` — one timestamp: when this device last had a network. It exists
 *    to wipe offline pins after thirty days offline, which is a licence obligation about *this
 *    device*, so a timestamp carried over from another one is either meaningless or actively
 *    dangerous. It re-seeds itself on the first cold start.
 *  - `reports/` and `last-crash.txt` — in flight to GitHub or already there.
 *  - The Coil image cache — in `cacheDir`, so out of scope anyway, and it is thumbnails.
 */
class Backup : LightSyncBackup() {
    override fun label() = "Phono"

    override fun stores() = listOf(
        FileStore(
            "Library",
            Contents(
                prefs = listOf("phono_pinned", "phono_library_backfill"),
                databases = listOf("phono_library.db"),
            ),
        ),
        FileStore(
            "Radio and podcasts",
            Contents(prefs = listOf("radio_stations", "phono_podcasts")),
        ),
        FileStore(
            "Play history",
            Contents(files = listOf("play-history")),
        ),
        FileStore(
            "Settings",
            Contents(
                prefs = listOf(
                    "phono_theme",
                    "phono_artwork",
                    "phono_backend_choice",
                    "phono_connect_aliases",
                    "phono_library_auto_download",
                    "phono_playback",
                ),
            ),
        ),
    )
}
