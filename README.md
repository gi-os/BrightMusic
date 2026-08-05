## Gio's LPIII fork — status as of 2026-08-03

LightPhono forks [jonathancaudill/phono](https://github.com/jonathancaudill/phono), a
Spotify/TIDAL client for the Light Phone III built on a patched librespot 0.8.0 (Rust,
via UniFFI) plus Jetpack Compose. Upstream owns the hard parts — the playback core, the
dual-auth scheme, the library sync — and this fork's own commits are almost entirely
additive: colour album art, a system keyboard, Spotify Connect casting, an NTS Radio
tab, and podcasts with auto-download. TIDAL is stripped; Spotify is the only backend
this fork ships, though the `PlaybackBackend` seam upstream built for two backends
stays in place so future upstream merges remain tractable.

**Current version:** `versionName` in `app/build.gradle.kts` is a static `0.11.0`; CI
overwrites it per build (see [Install](#install)). The latest published release is
`build-35` (`LightPhono v0.1.35`), tagged 2026-07-31. Note the APK in the local
`Light Phone Dev` folder, `LightPhono-v0.1.20.apk`, is fifteen builds behind that.

### What's working today

- Playback, library sync and the dual-auth scheme: unchanged from upstream, still the
  foundation everything else sits on.
- Colour album art on Now Playing and album/playlist headers, gated behind a one-time
  `WRITE_SECURE_SETTINGS` grant — degrades to greyscale, not a crash, if ungranted.
- System IME text entry, replacing the bundled LP3 Compose keyboard dependency.
- Spotify Connect casting (`DevicesScreen`, "Play on"), with the caveat that the phone
  itself cannot be a Connect target (see below).
- An in-app Output/Bluetooth picker, since `Settings.ACTION_BLUETOOTH_SETTINGS` does
  not resolve on LightOS; live-output switching works, connecting a new Bluetooth
  device works via a three-step fallback chain, both confirmed on device.
- NTS Radio (2 live channels + 16 mixtapes) sharing the same player screen as Spotify.
- Podcasts with per-show auto-download, resume points and retention limits, routed
  through the existing offline-download tables so no Room schema migration was needed.
- Podcast feeds are fully scrollable (v0.3). Episode lists and the saved-shows list page
  as they are scrolled instead of stopping at Spotify's fifty-item cap, read oldest-first
  on request — the same feed read from the far end, not a local re-sort of the part that
  happens to be downloaded — and SELECT takes a batch of episodes offline in one write.
  Episodes chosen by hand are exempt from retention, so picking twenty on a "Keep 3" show
  no longer deletes seventeen of them overnight.
- Downloads you can repair and top up (v0.4). Failed rows retry on a tap instead of being a
  dead end, collections and tracks show their artwork, a podcast can pull its next three
  episodes from either the show screen or its Downloads row, and everything already pinned
  when v0.4 lands is grandfathered out of retention. Episodes Spotify does not host itself —
  Serial and other RSS-distributed shows — are greyed and labelled rather than failing
  silently; librespot has no file id to ask for, so they cannot play here at all.
- Lock-screen media controls, fixed by moving the playback notification channel to
  `IMPORTANCE_DEFAULT` — LightOS ignores notification importance below 3.
- Downloads take over when signal goes (v0.1.35). Losing the network mid-album no longer
  ends the session at the first track that was not downloaded: playback walks on to the
  ones that were, and says "Not available offline." rather than buffering forever when
  there are none. Podcasts ride the same path, since an episode is pinned exactly like a
  track. A downloaded track needed no fix at all — the patched player prefers a pin for
  every load, so it was already coming off disk.
- Downloads that count, podcasts at your speed, and a library that fills itself (v0.5).
  A pin reports a percentage instead of a spinner: the Rust downloader hands back its fetch
  offset once per 256 KiB chunk over a new UniFFI callback, which is where a download spends
  its time — the decrypt-and-write pass afterwards is local work on bytes that already
  arrived. Every report reaches the screen; only one per two percent or half a second
  reaches Room, and those writes block rather than launch so a late `DOWNLOADING` row cannot
  land after the completion upsert and strand a finished download. Queued rows say "Queued"
  now, because the drain runs one track at a time and forty rows claiming to download at
  once described something that was not happening. Nothing was added to the schema.
- Podcast playback speed (v0.5). 1x through 2x and back via 0.8x, on
  `AudioTrack.setPlaybackParams`, which is the platform's sonic time-stretcher — faster
  speech, not higher speech. It takes the shuffle slot, which has nothing to shuffle on an
  episode loaded by itself, the same repurposing the skip buttons got. The rate is
  re-applied on every track rebuild, as routing changes, stalls and dead objects all cause,
  or an episode would drop back to 1x mid-listen; and it is declared to Media3, because the
  lock screen extrapolates position by wall-clock time times the rate. The v0.1.35 position
  work needed no change: `getPlaybackHeadPosition()` counts source frames consumed, not
  output frames produced, so pending latency stays in the same units at any rate.
- Liked Songs and Daily Mixes offline on their own (v0.5). Both off unless you turn them
  on, both checked on the daily alarm the podcast downloader already owns — Android clamps
  `setAndAllowWhileIdle` to roughly one firing every fifteen minutes per app, so a second
  alarm would compete for that budget and buy nothing. Liked keeps a rolling window of the
  newest N and lets go at the far end, except where a track also belongs to something pinned
  by hand. A Daily Mix is compared by membership rather than date: Spotify regenerates one
  whether or not it changed, and a mix that came back the same costs no audio.
- A progress bar that moves from the first play (v0.1.35). `AudioTrack.flush()` is a
  no-op unless the track is stopped or paused, so flushing mid-playback — which every
  seek and every user-initiated load does — left the playhead counting against a
  written-frame total that had just been zeroed. The pending-latency estimate read as an
  unsigned wrap, and it is subtracted from every reported position, so the bar sat at
  zero until a pause/play restarted the track.

### LPIII constraints that shaped this fork

- **The panel is a full-colour AMOLED under a forced daltonizer, not a black-and-white
  hardware limit.** That is the entire premise of the colour-art feature: LightPhono
  lifts `accessibility_display_daltonizer_enabled` for as long as a cover is on screen
  and restores it on backgrounding, so the rest of the phone stays mono and only the
  art gets hue. Needs `adb shell pm grant com.lightphone.spotify
  android.permission.WRITE_SECURE_SETTINGS` once; the write is silently swallowed
  without it.
- **No guaranteed system IME.** LightOS's own keyboard is an in-app Compose component,
  not an Android input method, so a stock device may have zero IMEs installed. This
  fork's text fields depend on one existing — check with `adb shell ime list -s`.
- **The hardware wheel is a relabelled optical sensor, not an encoder.** Notches arrive
  faster than a frame, so `hw/` banks each one as a debt and pays it off per frame, and
  discards the first notch after a pause since the wheel sits directly under a thumb.
- **A ~472dp-tall panel** means fixed-dp cover art pushes the transport controls off
  screen, so cover sizing goes through `BoxWithConstraints` rather than a fixed size.

### Changelog (this fork's own commits)

`git log` from `4293b18` onward is this fork; commits before it are inherited upstream
history, including the TIDAL feature-branch merge this fork later strips out.

- `82f5f44` — Answer the foreground-service deadline unconditionally, so the app stops closing itself on open
- `7ca7993` — Fix DownloadsScreen scope and simplify the progress FFI
- `4196611` — Show download progress, play podcasts faster, keep the library offline on its own
- `41e48c6` — Show the progress bar from the first play
- `c0cac78` — Fall back to downloaded audio when the network goes
- `e7d2cf1` — A cursor row is heterogeneous, so say so
- `6bac6bc` — Add a check workflow, so a change can be proven without shipping it
- `5fcb76b` — Keep a play history, and serve it to the journal
- `ff0ef25` — Give the playlist detail header a cover too
- `521f463` — Add the latest commit to the fork changelog
- `bbf9c09` — Rewrite README: fork status, LPIII constraints, and changelog as of v0.1.29
- `0e72717` — Keep a scrubbed position, and show playlist covers
- `d70ae16` — Put the media controls on the lock screen
- `132b804` — Say what the wheel actually needs
- `fe54c28` — Podcast scrubbing, and 15-second jumps in place of skip
- `a4061f6` — Scroll with the brightness wheel
- `7610329` — Fix podcast downloads, add show search, resume last track
- `dfc9dfe` — Podcast art at a usable size, and release dates a person can read
- `ebb5460` — Podcast retention, Liked tab, and offline fixes
- `0ceae1e` — Import TrackMetadata from data, not ffi
- `adf35f7` — Podcasts, with auto-download on release
- `b24adec` — Actually show playlist covers, and confirm before removing a download
- `ac73480` — Downloads first in the "…" menu, with a back button
- `e647872` — Stop the download control spinning when nothing is downloading
- `f545477` — One "Play on" list, pinned playlists, covers, and Playlists on open
- `3d7d2ac` — An icon, radio player chrome, Downloads under "…", and offline start-up
- `9f4fd15` — Add an NTS Radio tab that uses the same player
- `135f450` — Connect paired headphones from the Output screen
- `4d971d2` — Import the lazy items extension in BluetoothScreen
- `52b4faf` — Find LAN Spotify Connect receivers over mDNS
- `7bc5f25` — Stop hijacking the transport, fade on pause, own the output picker
- `df19b43` — Add a Bluetooth shortcut to the Play on screen
- `f9f41ee` — Drop TIDAL from the README and document the release flow
- `fd9642d` — Auto-release a signed APK on every push to main
- `bc32b2b` — Show album art in real colour
- `a0a34b7` — Assert on Month, not the localised label, in LibraryDateIndexDebugTest
- `f30999c` — Stop LibraryDateIndexDebugTest writing to an absolute path in upstream's checkout
- `1042c5f` — Retarget the collection-URI test off BackendChoice.TIDAL
- `ebf86c2` — Fix brace imbalance left by the TIDAL strip in PlaybackController init
- `05b6f35` — Strip the TIDAL backend; fix the CI host bindings build
- `4293b18` — Fork as LightPhono: album art, system keyboard, Spotify Connect

Two things worth knowing if you cast: the phone **cannot be a Connect target** — the
Rust core builds librespot with `default-features = false` and never links
`librespot-connect`, so no Spirc loop runs and Spotify never sees the phone as a
device. And remote state is polled every 5s rather than pushed, because Spotify has
no public push API for player state outside its undocumented dealer websocket.

---

# LightPhono — Spotify Client for Light Phone III

A fork of **[jonathancaudill/phono](https://github.com/jonathancaudill/phono)**. Everything
that matters — the patched librespot playback core, the dual-auth scheme, the library sync —
is upstream's work, and upstream is where the hard parts live.

This fork adds colour album art, the system keyboard, Spotify Connect casting, NTS Radio and
podcasts with auto-download, and drops upstream's TIDAL backend.

### What this fork changes

**Album art, in colour.** Upstream shows covers in list rows only; the player had none.
LightPhono puts a cover on Now Playing and on album/playlist detail headers — and shows it
in **real colour**.

That is possible because the LPIII panel is a full-colour AMOLED: the black-and-white look
is Android's accessibility daltonizer pinned to monochromacy, a secure setting. While a
cover is on screen LightPhono clears it and puts it straight back afterwards, so the phone
is mono everywhere else and colour exactly where the art is. Requires a one-time adb grant
— see [Album art in colour](#album-art-in-colour).

**Settings → Album art** picks the treatment:

| Mode | What it does |
| --- | --- |
| **Colour** (default) | Untouched art, forced greyscale lifted while a cover is visible |
| **Dithered** | Rec.709 luma + contrast curve + 8×8 Bayer dither; phone stays mono |
| **Greyscale** | Luma + contrast curve, no dither; phone stays mono |
| **Off** | No artwork, upstream's text-only look |

The two greyscale modes apply a contrast curve because a straight luminance pass reads
muddy on this panel, and the dither trades real tonal levels for apparent detail — it hides
the banding plain greyscale shows in skies and gradient sleeves.

**System keyboard.** Text entry uses the Android IME instead of the bundled LP3 Compose
keyboard, dropping the `com.thelightphone.lp3keyboard:ui` dependency.

**Building needs a GitHub Packages PAT.** Since v0.11.0 the wheel/hardware-key layer and the
LightSync backup provider come from `com.gios:light-common`, which is published to GitHub
Packages — and GitHub Packages has no anonymous read even for public packages. Put a PAT with
`read:packages` in `local.properties` as `gpr.user` / `gpr.key`; CI uses `GPR_USER` /
`GPR_TOKEN`, falling back to the run's own token.

**Spotify Connect casting.** A Devices screen ("Play on") lists your other Spotify devices
and hands playback to one, with an Output picker alongside for local audio routing. While a
device is active the transport drives the speaker and every screen shows what it is playing.
Needs two extra scopes — see [Spotify Connect](#spotify-connect) below.

**The brightness wheel scrolls.** Turn the wheel and the list you are looking at moves, a
notch at a time, without a thumb over the rows. It works on every list in the app — library,
detail, queue, downloads, radio, search results, Settings — and on the sign-in pages, which
are taller than the panel and put their buttons at the bottom.

**Nothing else has to be installed for that.** Light patched
`/system/usr/keylayout/Generic.kl`, so a notch arrives as an ordinary key event, delivered to
whichever app holds focus, and LightPhono reads it itself. No companion service, no extra
permission, no root.

The wheel is not a rotary encoder: it is an optical sensor that emits one key pair per notch,
faster than a frame, so applying each notch as it lands gives a stack of jumps with nothing
for the eye to follow. `hw/` puts every notch into a debt and pays off a share of it each
frame, which turns a fast spin into one continuous sweep. The wheel also sits under a thumb
and catches stray brushes, so the first notch after a pause is held back until a second one
confirms it.

Only the turns are handled here. The click, the camera key and brightness belong to
[LightControl](https://github.com/gi-os/LightControl), which owns them across the phone.
Now Playing is deliberately left out: it has nothing to scroll, and a notch there is not
quietly repurposed into volume or a seek, because that would be a second thing the same
gesture means depending on the screen.

LightControl is optional, and it is where the rest of the wheel lives: hold the wheel in and
turn for brightness, tap it for the flashlight, press the camera button for the camera — each
of those rebindable, tap and hold separately, to any app you have installed. It also hands
brightness, or a synthetic-swipe scroll, to apps that carry no wheel handling of their own.

Installing it does not cost you the scrolling above. LightControl passes bare turns straight
through to `com.gios.*`, `com.lightfastread`, `com.lightrss.reader` and
`com.lightphone.spotify`, because per-notch scrolling inside an app beats anything reachable
from outside it. That last id is this app. LightPhono used to get its pass by accident, for
looking like Light's own software, which also meant the click and the camera button were left
alone here; LightControl now names it deliberately, so the turns still scroll and the button
bindings work too.

```bash
# Optional: LightControl, for brightness, the flashlight and the camera button
adb install -r LightControl-v1.0.x.apk

# The key service. NOTE: this setting is a list, and this command REPLACES it —
# if you also run LightVoice's push-to-talk, colon-join both components instead.
adb shell settings put secure enabled_accessibility_services \
  com.gios.lightcontrol/com.gios.lightcontrol.keys.ControlService
adb shell settings put secure accessibility_enabled 1

# Brightness, and the level readout + opening apps from the service
adb shell appops set com.gios.lightcontrol WRITE_SETTINGS allow
adb shell appops set com.gios.lightcontrol SYSTEM_ALERT_WINDOW allow
```

Latest APK: https://github.com/gi-os/LightControl/releases/latest

---

Thanks to **[Vandam Dinh](https://github.com/vandamd)** — especially
[Echo](https://github.com/vandamd/echo) — for the Light Phone UI
patterns and product direction this client builds on.

An independent, minimal music client for LightOS.


> Requires Spotify **Premium**. This is not something we have *any* interest in working
> around, so please do not ask!

**New developer? Agent?** Read [docs/README.md](docs/README.md) and [AGENTS.md](AGENTS.md)
before changing Spotify/librespot code.

## Requirements for this fork

**A system keyboard must be installed and enabled.** LightOS ships its keyboard as an
in-app Compose component, not as an Android IME, so there may be no system IME on the
device. Check before installing:

```bash
adb shell ime list -s        # enabled input methods
adb shell ime list -a -s    # every installed input method
```

If the first command prints nothing, sideload one (HeliBoard from F-Droid is a reasonable
greyscale-friendly pick) and enable it:

```bash
adb install HeliBoard.apk
adb shell ime enable <id from `ime list -a -s`>
adb shell ime set <same id>
```

Without an IME, tapping a text field opens the editor with no way to type.

## Install

Every push to `main` publishes a signed APK to
[Releases](https://github.com/gi-os/LightPhono/releases/latest), tagged `build-N` with
`versionCode` = the CI run number, so it upgrades in place. One asset per release, which is
what Obtainium needs to pick the right file.

Obtainium: add `https://github.com/gi-os/LightPhono`. Then do the one adb grant below for
colour art, and check you have a system IME.

Every build is signed with one stable key held in repo secrets, and CI fails if the
certificate ever drifts from `signing-fingerprint.txt` — a changing certificate is what
turns into Obtainium's unhelpful `Failure: Invalid` days later.

## Album art in colour

Colour needs `WRITE_SECURE_SETTINGS`, which is `signature|privileged|development` and so
cannot be granted by installing. One time, over adb:

```bash
adb shell pm grant com.lightphone.spotify android.permission.WRITE_SECURE_SETTINGS
```

Without the grant nothing breaks — the write is swallowed and covers stay greyscale, the
same as picking Greyscale in Settings.

How it works: LightOS pins `accessibility_display_daltonizer_enabled` to 1 with mode 0
(simulate monochromacy). That is a SurfaceFlinger colour matrix, so clearing it shows true
colour instantly with no restart. LightPhono holds it cleared while a cover is composed and
restores the original mode when the last one leaves, and it also drops back to mono when
the app leaves the foreground, so the rest of the phone is never left in colour.

Two consequences worth knowing:

- **It is display-wide, not per-view** — Android cannot colourise a single surface. It
  reads as art-only because LightPhono's palette is greyscale by construction, so the cover
  is the only thing on screen with hues in it.
- **A process death while a cover is open can leave the phone in colour** until the app
  next runs. Nothing is persisted to guard against it.

Same trick, same caveats, as LightChat's image viewer.

## Podcasts

A Podcasts tab lists the shows you follow on Spotify. Tap a show for its episodes, tap an episode to
play — it resumes where you left off, and the list shows "22 min left" rather than the total.

**Auto-download on release** is per show: the arrow on a show, or a long-press on it in the list.
LightPhono then keeps the newest episodes on the phone so there is always something to listen to with
no signal.

It checks at two moments, because neither alone is enough: on app start, which catches the case where
you open the app on Wi-Fi before leaving, and once a day from an alarm, so an episode that lands
overnight is on the phone before the morning commute without the app being opened. The alarm uses
`setAndAllowWhileIdle`, the only scheduling call that fires in Doze.

New episodes are found by **id, not release date** — a date says when something was published, not
whether this phone already has it, and a show publishing twice in a day would otherwise skip one.
Enabling a show grabs its two newest episodes rather than a back catalogue, and each check is capped at
five, so a show that dumps a season overnight cannot fill the phone.

Episodes download into the same tables as music and appear in Downloads alongside everything else.
That is deliberate: podcasts added **no database change at all**. `PhonoDatabase` uses
`fallbackToDestructiveMigration()`, so a new entity would mean a version bump that wipes your offline
music — so browsing is online-only, and settings, resume points and last-seen ids live in
preferences. Resume points are kept locally rather than read from Spotify's `resume_point`, which
would need the `user-read-playback-position` scope and a re-authorize, and which would not work on a
train anyway.

Saved shows come from `user-library-read`, already granted, so **no re-authorize is needed**.

Worth knowing: not every podcast on Spotify is Spotify-hosted audio. Episodes Spotify will not stream
to your account or market are listed greyed rather than hidden, so a gap in a feed is explained.

## Output and Bluetooth

Bluetooth and local outputs are the **top section of "Play on"**, above Spotify Connect, because
plugging in headphones is a daily act and casting to a speaker an occasional one. There is no separate
Output screen to dig into.

**Hold the cast button** on the player to connect straight to your favourite headphones without
opening anything. Set the favourite by long-pressing a paired device in "Play on"; long-press again to
clear it. With no favourite set, holding the cast button just opens the picker.


**Output** (the Bluetooth glyph on "Play on") lists every audio output that is live right now, and
tapping one routes *LightPhono's* audio to it. That part genuinely works:
`AudioTrack.setPreferredDevice` is the one public API an app has for choosing its own output, and the
choice is re-applied across the track rebuilds that route changes and stalls cause.

**Paired headphones are tappable** and LightPhono will try to bring them up. Android has no public API
for this, so `BluetoothConnector` tries three things in order and tells you which one it got:

1. **Reflective `connect` on the A2DP proxy** (obtained via the public `getProfileProxy`). Works where
   `BLUETOOTH_PRIVILEGED` is not enforced on that path, or where the hidden-API policy is relaxed.
2. **`createBond()`** if the device is not really bonded — pairing is public and brings audio up with it.
3. **`fetchUuidsWithSdp()`** as a nudge: it opens an ACL link, and Android's own A2DP state machine
   very often connects a bonded audio device once it is reachable.

Then it waits for the device to actually show up as connected (`getConnectedDevices`, which *is*
public) and routes playback to it, so connecting and selecting are one tap rather than two.

If step 1 is refused on your phone — `BluetoothA2dp.connect()` is `@hide` and needs
`BLUETOOTH_PRIVILEGED`, which is `signature|privileged` and so cannot be adb-granted the way
`WRITE_SECURE_SETTINGS` can — one command opens it up:

```bash
adb shell settings put global hidden_api_policy 1
```

Without that, steps 2 and 3 still often work; when nothing does, the screen says so rather than
spinning.

Anything in the Output list is connected, and says so — a connected pair of headphones that is
neither selected nor currently routed used to show no label at all, which read as "not connected".

`BLUETOOTH_CONNECT` is requested at runtime and only affects whether paired devices can be shown *by
name*; without it, switching between live outputs still works.

## Spotify Connect

Casting needs two scopes upstream does not request: `user-read-playback-state` and
`user-modify-playback-state`. They are in the Step 2 authorize URL, so a fresh setup picks
them up automatically.

**Upgrading from upstream phono?** Your stored token was minted without them, and refreshing
returns a token with the original grant's scopes — so it can never gain them. The Devices
screen detects this (403 insufficient scope) and offers **RE-AUTHORIZE**, which drops the
token but keeps your Client ID and Secret, so you only redo the authorize tap.

The Bluetooth button on that screen opens an **in-app Output picker**. It was a
`Settings.ACTION_BLUETOOTH_SETTINGS` intent, which does not resolve on LightOS, so the app owns
the screen now. See [Output and Bluetooth](#output-and-bluetooth).

### Receivers that only show up in the desktop app

If an AV receiver or networked speaker appears in desktop Spotify but not here, that is a Web API
limitation rather than a bug. `/me/player/devices` returns only devices **registered to your account**.
A receiver announces itself over mDNS as `_spotify-connect._tcp` and the desktop and mobile apps
discover it *locally*; until one of them logs it into your account, Spotify's backend has never heard
of it and no Web API call can see it.

LightPhono browses for those itself, lists them under **On this network**, and **tapping one signs
your account into it** (v0.6). That is the ZeroConf `addUser` handshake: a Diffie-Hellman exchange
against the receiver's `publicKey`, then this phone's own stored credential re-sealed for the
receiver's `deviceID` and posted to it. The receiver opens its own session with Spotify, registers
with your account, and the app waits for it to appear in the device list before transferring playback
to it — so one tap goes from "found on Wi-Fi" to "playing on the amp".

Worth knowing:

- **It hands over a credential.** The same one the desktop app hands a speaker when you tap it there —
  a reusable credential, not your password — but only ever to a device you tapped, and only after that
  device proves it speaks the protocol.
- **A receiver with no `publicKey` or no `deviceID` stays greyed.** There is nothing to seal a login
  against, so the row remains informational rather than pretending to be tappable.
- **The endpoint's path comes from the mDNS TXT record's `CPath`**, which is where the spec puts it and
  which is not guessable. Four receivers on one real network used four different paths: a Cambridge
  Audio CXN100 on `/spotify_zeroconf`, a Sony STR-DN1080 on `/goform/spotifyConnect`, a WiiM AMP on
  `/zc` and a PS5 on `/spConn`. A
  receiver that answers nowhere stays listed as unreachable with its address, rather than vanishing.
  The section header is always shown while the picker is open, so "nothing found" reads as nothing
  found instead of as a missing feature.
- **Cleartext HTTP to the LAN had to be permitted.** A receiver speaks plain HTTP on a private
  address, and the app's network security config allowed cleartext for loopback only — so every
  `getInfo` died with "CLEARTEXT communication not permitted" before a byte left the phone, which the
  UI could only report as "did not answer". There is no way to allowlist an address range, so the
  default is inverted and every remote host is denied cleartext individually instead. Playback is
  unaffected either way: librespot opens its own sockets from Rust and the platform policy never sees
  them.
- **mDNS resolves run one at a time.** `NsdManager.resolveService` cannot be called concurrently;
  firing one per discovered service makes Android cross the results, which on a network with several
  receivers produced a single row carrying one device's name and another's IP address.
- **If a receiver refuses, it says why.** `ERROR-MAC`, `ERROR-INVALID-PUBLICKEY` and the rest are shown
  in the receiver's own words instead of a generic failure, because those names are the difference
  between a wrong key and a wrong account.
- **The blob's plaintext layout was measured off the real client.** Spotify has never published it, and
  both librespot and librespot-java skip its tag bytes when decoding — so their decoders accept a wrong
  one and real eSDK hardware does not. It was captured by advertising a fake receiver holding a known
  DH key and tapping it in the Spotify desktop app: `49 <len> <username> 50 <auth_type> 51 <len>
  <credential>` plus ANSI X.923 padding, with `auth_type` 1 (stored credentials). Two of the three tags
  had been guessed wrong. A golden test now pins every byte.

Either way the old route still works: start playback on the receiver once from any Spotify app and it
registers with your account.

Browsing runs only while the picker is open — mDNS needs the foreground, and a permanent browse would
cost battery for a screen nobody watches.

Two limits worth knowing up front:

- **You cannot cast *to* the Light Phone.** The Rust core builds librespot with
  `default-features = false` and no `librespot-connect`, so no Spirc loop runs and Spotify
  never sees the phone as a device. "This phone" in the picker resumes the local engine
  instead of transferring.
- **Remote state is polled every 5s**, because Spotify has no push API for player state
  outside its undocumented dealer websocket. A skip on the speaker can take a few seconds
  to show up on the phone. Polling only runs while a remote device is active.

## How is this different from Echo?


vandam rocks. Basically, this has a few extra features, colour album art, and doesn't
require the Spotify app to be installed.

# Setup

## Spotify
The app uses **dual authentication** for Spotify:

1. **Step 1 — Playback (librespot):** WebView login with Spotify’s first-party Keymaster
   client for audio streaming. No developer dashboard setup for this step.
   Redirect: `http://127.0.0.1:8898/login`.
2. **Step 2 — Web API:** Create your own app at
   [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard) and enter the
   **Client ID** and **Client Secret** on the Step 2 gate after playback login.
   Redirect: `http://127.0.0.1:43821/callback`.

### Create your Spotify Developer app

1. Go to [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard)
2. Click **Create App**
3. Fill in app name and description
4. Set **Redirect URI** to `http://127.0.0.1:43821/callback` (must match exactly)
5. Select **Web API** under “Which API/SDKs are you planning to use?”
6. Accept terms and click **Save**
7. Open **Settings** and copy your **Client ID** and **Client Secret**
8. Click **Save**

### Configure the app (Spotify)

1. Complete **Step 1** (playback login)
2. On **Step 2**, enter your Client ID and Client Secret:
   - Type manually, or
   - On a computer, open **[jonathancaudill.github.io/phono](https://jonathancaudill.github.io/phono/)** to generate a QR code, then tap **Scan QR** on the phone
3. Tap **Connect Web API** and authorize when prompted

The setup page runs entirely in your browser; it's just a static page. The QR
code encodes your client secret in plain text, so be careful sharing it :)

**PLEASE NOTE:** Credentials will expire around 6 months depending on 
Spotify dev app restrictions. Just rotate the secret and redo steps 2-3!

---


## LEGAL
- Spotify "offline" playback is simply an extra large streaming cache, not an actual raw file downloader. If you haven't been online in 30 days, all downloaded playlists and albums will be wiped to protect Spotify's TOS.
- A Spotify Premium subscription is required for ***any*** part of LightPhono to work.


# boring architecture descriptions below. literally no point in reading any further unless you wanna make a pr

## Repository layout

```
rust/
  spotify-core/                 # UniFFI engine: session, player, queue, Spotify downloads
  librespot-core-patched/       # Keymaster/desktop identity (PATCHES.md)
  librespot-playback-patched/   # Buffering API, sink lifecycle (PATCHES.md)
  librespot-audio-patched/      # CDN fetch resilience (PATCHES.md)
app/                            # Android (Kotlin + Jetpack Compose)
setup/                          # GitHub Pages: Spotify Web API credential QR generator
docs/                           # Architecture, offline downloads, field tests
scripts/build-rust.sh           # Cross-compile + UniFFI Kotlin bindings
```

Librespot crates are pinned to **=0.8.0**. Do not bump without re-validating every patch.

## Architecture

### Backend selection

Spotify-only in this fork, but the seam upstream built for two backends is kept, because
removing it would conflict with every future merge from upstream.

- `PlaybackController` binds one `PlaybackBackend` + `MusicRepository`: librespot UniFFI +
  `SpotifyRepository` / Web API + spclient.
- `BackendChoice` is a single-value enum; `BackendPreferences.ensureSpotify()` pins it on
  first launch and rewrites a stored `TIDAL` left by an upstream phono install.
- Soft feature gates via `BackendCapabilities` (downloads, quality UI).

### Spotify playback (Rust)

- `LibrespotEngine` (UniFFI) owns session, player, and queue.
- Keymaster OAuth via WebView. Three client-identity surfaces must agree as
  Keymaster/desktop — see `AGENTS.md`.
- **Audio output (Path C):** decode → SPSC ring → drain thread → JNI →
  `PhonoAudioTrackSink` → `AudioTrack`. Details: [docs/audio-sink.md](docs/audio-sink.md).
- **Session recovery:** seamless rebuild with queue/position restore.
  [docs/future/session-reconnect.md](docs/future/session-reconnect.md).

### Spotify Connect (casting)

- `ConnectController` polls `GET /me/player` every 5s while a remote device is active and
  overlays the result onto `PlaybackUiState`, so screens never branch on remote vs local.
- Transport in `AppViewModel` routes by destination; `DevicesScreen` lists and transfers.
- The phone is **not** a Connect target: no `librespot-connect`, so no Spirc loop.

### Android (shared)

- `PlaybackService` + MediaSession; `PlaybackController` owns audio focus, network
  policy, stall UX, and the offline-download façade.
- **StreamingPolicy:** network tiers (OFFLINE → GOOD_UNMETERED) bank the rest of the
  current track, then prefetch. Wi‑Fi must stay visible **2 minutes** before it is
  preferred over cellular (avoids blip handoffs).
- Spotify metadata: Web API + `NativeMetadataGateway` (playlists/artists via spclient).

`NativeInit` order (Spotify): `loadLibrary` → `initAndroidContext` → `registerAudioSink`.

## Offline downloads

Pin albums/playlists from headers, hold menus, or the **Downloads** tab. Shared Room
index; the engine is UniFFI decrypt-to-Ogg behind a foreground service, writing
`filesDir/spotify-downloads/{id}_{quality}.ogg`.

Streaming quality and download quality are independent (changing download quality does
not rewrite existing pins). Clear Cache wipes stream LRUs only — pins stay.

**TOS guard:** if LightPhono has not seen a network for **30+ days**, offline pins are wiped
(`OfflinePinHygiene`). Credentials and stream cache are untouched.

Details: [docs/offline-downloads.md](docs/offline-downloads.md).

## Caching

### Library (Room)

Liked tracks, saved albums, playlists — head-check delta sync, parallel page fill.

### Detail / search

Pinned Room detail cache (24 h TTL for saved/owned); ephemeral browse cache. Search
per-query in-memory (5 min); filter chips reuse the cached response.

### Auth tokens

- Spotify playback: librespot credentials in `filesDir/spotify-cache/`
- Spotify Web API: `EncryptedSharedPreferences` with refresh

### Audio

- **Spotify stream:** Ogg under `filesDir/spotify-cache/` (`buffer_current_to_end` /
  `prefetch_upcoming` on good networks)
- **Pins:** `spotify-downloads` (not cleared by Clear Cache)

## Build

Prerequisites:

- Rust (rustup) with Android targets: `rustup target add aarch64-linux-android x86_64-linux-android`
- `cargo install cargo-ndk`
- Android NDK; export `ANDROID_NDK_HOME`
- JDK 17, Android SDK (compileSdk 35), Gradle

```bash
# Cross-compile Rust + generate Kotlin bindings (AudioTrack backend by default)
bash scripts/build-rust.sh

# Build and install
./gradlew :app:installDebug
```

**Rodio fallback** (emulator debug): `USE_AUDIOTRACK_SINK=0 ./scripts/build-rust.sh` and match
`USE_AUDIOTRACK_SINK` in `app/build.gradle.kts`.

**Host tests** (ring buffer): `cd rust/spotify-core && cargo test pcm_ring`

## Key gotchas

- Call `NativeInit.initAndroidContext` before constructing the Spotify engine.
- **Do not mix Spotify redirect URIs:** Step 1 → `127.0.0.1:8898/login`; Step 2 → `127.0.0.1:43821/callback`.
- **Do not use the Keymaster token for Web API** — metadata must use the BYO dev-app bearer.
- Playlist/artist screens on Spotify require Step 1 (native spclient).
- **minSdk 26.** Audio focus in `PlaybackController`.
- `PlaybackService` must `startForeground()` promptly after `startForegroundService()`.

## Reliability

| Layer | Mechanism |
|-------|-----------|
| Session (Spotify) | Monitor + seamless rebuild; `force_reconnect_check()` on network change |
| Network policy | StreamingPolicy tiers + 2‑minute Wi‑Fi preference gate |
| Decode / bank | Spotify buffer/prefetch |
| Audio output | Ring + drain; stall recovery |
| APIs | Token refresh, HTTP 429 `Retry-After` where applicable |

Field validation: [docs/audio-sink-baseline-metrics.md](docs/audio-sink-baseline-metrics.md)

## Documentation index

| Doc | Contents |
|-----|----------|
| [AGENTS.md](AGENTS.md) | Hard rules, Spotify auth, diagnostics — read before coding |
| [docs/README.md](docs/README.md) | Developer onboarding index |
| [docs/offline-downloads.md](docs/offline-downloads.md) | Offline pins |
| [docs/audio-sink.md](docs/audio-sink.md) | Phase C AudioTrack architecture |
| [docs/future/](docs/future/) | Researched future work (session reconnect, backend move) |
