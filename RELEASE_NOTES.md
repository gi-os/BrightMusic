## BrightMusic v0.58 — the network calls stop borrowing the UI thread

**The Web API client was blocking whichever thread called it.** Every read and write in
`SpotifyWebApi` that was not already `suspend` ended in `runBlocking { executeWithRetry(request) }`,
which parks the calling thread until Spotify answers. That is fine on a worker and an ANR on the
main thread, and nothing in any signature said which one you were on: the chain from a screen's
event handler down to the blocking call ran four hops through the view model, the playback
controller and the repository, and compiled without a word. The shortest of those chains was track
metadata — tapping into a queue item whose title had not been fetched yet asked Spotify for it from
`viewModelScope`, which is the main dispatcher.

Every accessor in the client is a real suspend function now and hops to the IO dispatcher itself, so
no caller has to remember to. `MusicRepository` follows: anything on it that can reach the network
says `suspend`, which means the compiler is what stops this coming back rather than a code review.
The two `runBlocking` calls that remain are at genuine boundaries and are staying — the download
progress callback (called by the Rust fetch thread, which is blocked on that chunk either way, and
where the ordering the block buys is what stops a finished download showing as in-progress forever),
and the tests.

**One region-locked save no longer stops the library syncing.** Spotify returns a saved track or
album with a null payload when the item is not available in your market. Both mappers read those as
`!!`, so a single unavailable item in a page of fifty threw out of the mapper, out of the page
insert and out of the whole sync — which then stopped at that page and never got past it, on every
retry, because the offending row sits at the same offset every time. Unavailable rows are dropped
now, and dropped *in place*: the position is left empty rather than pulling everything after it up
by one, because that position is what Liked Songs is ordered by and the next page still starts at
its own offset.

**Logging out stops leaving live system callbacks behind.** The playback controller registers three
things with the platform when it is built — the becoming-noisy receiver, the default-network
callback and the audio-device callback — and nothing ever handed them back. The controller is
dropped and rebuilt on logout, so every logout left three callbacks pointing at a dead controller
and the next login registered three more; the network one kept calling for a reconnect against an
engine that had been torn down, which is the reconnect-churn shape this app already fought once on
the subway. There is a `release()` now, and the one path that genuinely abandons the instance calls
it.

Also: the release workflow no longer runs on pull requests. It had a bare `pull_request:` trigger
while holding `contents: write` and decoding the signing keystore onto the runner, and one real pull
request had already run it. Compiling a pull request is the check workflow's job, and it now has the
trigger to do it. Every action in every workflow is pinned to a commit SHA rather than a moving tag.

## Phono v0.52 — Radio that proves it is playing

**"Success" now requires sound to be possible.** Routing a station to the bridge could succeed
in every way that didn't matter: the server answered, the queue add returned 200 — and the room
stayed silent, because every speaker was toggled off, or the stream never actually opened.
Starting bridge radio now checks that at least one speaker is selected, verifies the player
reaches "play" (nudging it, briefly re-checking), and treats anything short of that as failure —
which sends the station to the phone's own speaker instead of nowhere. The reason lands in the
bridge error line on the output picker.

If radio still comes up silent on this build, the next thing to read is `logcat | grep -E
"BridgeCtrl|RadioController"` and OwnTone's own log — at this point the app only claims playback
the server has confirmed.

## Phono v0.51 — The bridge stops lying

**Starting radio now pauses Spotify wherever it actually is.** "Pause Spotify first" paused the
*local* engine — which, when music was already on the HomePods through the bridge's Connect
device, was pausing silence. The remote kept playing over the station. Radio start now pauses
the active Connect device too.

**The cover follows the song.** Radio artwork preferred the station's own image, which for WNYU
is the same logo on every poll — so the matched track's cover never got a turn. The matched
track's art now leads, and station art only wins when it says something the match cannot: a live
show's own cover (NTS), recognisable because it differs from the station's fixed logo.

**Play after pause on AirPlay re-sends the stream.** OwnTone left paused on a live HTTP stream
is usually holding a dead socket; `/player/play` on it looks pressed and stays silent. Resume
now re-queues the station URL — live radio has no position to lose. Starting a stream also
pauses OwnTone first (so `pipe_autostart` cannot outvote the queue) and verifies the player
actually reached "play", nudging it once if not.

**"Connected" now means connected.** The settings label was read straight off the saved config,
so it said Connected from anywhere on earth. The bridge is now probed — on configure, on every
speaker refresh, and each time Settings opens — and the label reports what the probe found:
Connected, Not reachable, or Checking.

## Phono v0.50 — Radio joins the new player, and the bridge learns to stop

**The new Now Playing screen now carries radio.** The expanded player — three lines on the
left, square cover on the right, aurora behind — was gated to Spotify tracks, so tuning into a
station dropped you back to the old text-only screen. A station has everything the layout wants
(name, show, artwork), so radio now uses it too. The parts a live stream cannot support fall
away rather than sitting there broken: no scrub bar (there is no position), no track skips (a
skip *left radio*, which read as a crash), sideways swipes disarmed for the same reason, and
the bottom row is the radio pair — the heart for the matched track once one is identified, and
the output picker. The placeholder glyph is a radio, not a music note, and the third line no
longer shows the previous Spotify track's album.

**The speaker bridge's transport actually transports.** While radio played through
OwnTone → AirPlay, pause flipped the button and changed nothing, play was a dead press, and
stopping radio left the HomePods playing a station the phone said was gone. The radio
controller now hands external playback its own pause/resume/stop, wired to OwnTone's player —
so the buttons on the phone, and the lock-screen row, control what is actually making sound.

**A configured bridge is not a reachable one.** Tapping a station away from home sent the
stream into the void: the phone sat silent while claiming to play. The bridge call now has LAN
timeouts and reports failure, and radio falls back to playing on the phone. Starting bridge
radio also pauses local Spotify first — the two used to play over each other — and handing
playback to a Connect device stops radio so the pipe is free for `pipe_autostart`.

Housekeeping: every OwnTone HTTP response is now closed (each call leaked its connection),
the "Playing on …" label is only suppressed for the bridge's own Connect device instead of all
of them, and the dead `withBridgeRadio`/`transferPlaybackToBridge` leftovers are gone.

## Phono v0.49 — Two crashes that were never about the music

**Opening the app after a logout took it down, every time.** Logging out clears the stored
backend choice — a leftover from upstream phono, where logout returned you to a Spotify/TIDAL
picker that would set it again. LightPhono has no picker: the screen recreates, the controller is
rebuilt immediately, and the rebuild found no choice on file and refused to continue. The choice
is a formality in an app with exactly one backend, so the rebuild now pins Spotify itself — the
same thing App startup has always done — instead of insisting somebody pick from a list of one.

Fixes [light-reports#16] — "It closed itself", on relaunch after signing out.

**The playback service could crash the app just by being looked at.** The service promotes itself
to the foreground the moment it is created, so the system's media controls and the five-second
foreground deadline are both satisfied whichever way playback starts. But a service is also
*created by being bound to* — and when that happens while Android considers the app
background-restricted, there was no foreground start and no exemption, and the promotion is a
`ForegroundServiceStartNotAllowedException` instead. A bound service has no deadline to answer,
so the service now notices the refusal, stays a plain service, and lets the next legitimate start
or Media3's own notification pass promote it as before.

Fixes [light-reports#17] — "It closed itself", on a Samsung running Android 16.
