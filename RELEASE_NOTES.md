## BrightMusic v0.66 — the alarm was the wrong kind

**Overnight downloads actually start now.** The daily podcast and library check rode an inexact
alarm, and an inexact alarm is not on the list of things that let a broadcast receiver start a
foreground service from the background. So the alarm fired, the receiver ran, and the download
service it tried to start was refused — silently, every night, which is why mornings had nothing
new in them. The check uses an exact alarm now, the same pattern the sleep timer has always used,
with the inexact call kept only as the fallback for a phone that has denied exact alarms.

**One bad moment of network no longer silences a station's track titles for good.** The Icecast
now-playing lookup keeps a list of hosts that don't serve the status endpoint, so it stops asking
them — which is right. But it added a host to that list on *any* failure, including a timeout in a
tunnel or a dropped Wi-Fi handoff, and the list never expires. One blip and that station showed no
titles until the app was killed. A transport failure is just retried on the next poll now; only a
host that actually answered with something unusable gets written off.

**Paused radio stops asking what's playing.** The metadata poll kept hitting the network on its
schedule while the stream was paused, fetching titles nobody was listening to. It sleeps through
the pause and picks up where it left off on resume.

**Fewer disk writes while music plays.** The resume position was committed to storage every second
for the entire length of playback. It lands at most once every ten seconds now. Pause, scrubbing,
and leaving the app still write immediately, so the position you come back to is the one you left.

**The lock-screen row stops probing usage stats when it can never show.** Deciding whether the row
belongs on screen means asking the system which app is in front — a usage-events sweep, every
700 ms while the screen is on. With the setting off, the overlay permission missing, or nothing
loaded, the answer could not matter and the sweep ran anyway. Those cases skip the query now.

**Opening the queue no longer redraws it once per track.** Filling in titles for a fresh queue
published a new snapshot after every single track resolved — a long queue meant dozens of full
redraws in a row. It publishes every tenth resolution and once at the end, so slow networks still
see it fill in.

**Small hygiene.** The download drain reads one row and a count instead of pulling the whole table
twice per drained track. Two engine reads moved out of state-update lambdas, which retry on
contention and must not carry side effects. A volume write on the audio sink takes the same lock as
every other access to the track it touches. Two dead assignments in the offline-handoff watchdog
are gone.

## BrightMusic v0.63 — what upstream fixed while we were elsewhere

BrightMusic forked from [phono](https://github.com/jonathancaudill/phono) on 23 July and has run 155
commits ahead since. Upstream landed 23 commits in that time, six of them code. This takes everything
in those six that applies to a Spotify-only fork, and keeps our version wherever ours was already the
better one.

**Reordering the queue moves the track you tapped.** A row index is only true for the frame it was
drawn in, so a second tap arriving before the redraw moved whatever had slid into that row. Reorders
are identified by URI now, with the index kept only as a hint, and a burst of taps is applied as one
batch under the transport lock instead of each tap racing the engine on its own — one redraw for the
burst, and no chance of interleaving with a skip or an end-of-track.

**The queue arrows at the edges do something.** Down on the last queued track pushes it out of Next
in queue and into the front of the context; up on the first context track pulls it into the queue.
Both were greyed out because neither existed.

**Reordering while a queued track is playing swapped the wrong pair.** The manual queue and the play
order drift apart the moment the current track is itself a queued one, and the reorder was indexing
the wrong one of the two. They are kept in step now, and "Clear queue" while a queued track plays no
longer leaves the player pointing past the end of its own queue.

**Shuffle from the middle of an album shuffles the album.** It only ever pooled the tracks *after*
where you were, so shuffling from track 40 of 50 shuffled ten. Toggling shuffle also used to eat
whatever you had queued up; the manual queue survives it now, and survives a repeat-mode change too.

**Resume no longer sends the progress bar back to 0:00.** The engine re-announced the current track
on every `Playing` event, and everything downstream treats that as a new track: position zeroed,
duration dropped, and — worse for us — the pending seek target discarded, which quietly undid a scrub
made while paused. The engine only announces real track changes now, and the app ignores a
"track changed" that names the track already playing.

**Liking a song no longer costs a full library re-download.** The library head-check compared a
timestamp we had written ourselves with `Instant.now()`, which never matches Spotify's format — so
every like, save, or unlike poisoned the check and the next launch wiped the cache and re-paged the
whole library. Same for any playlist whose owner name had not resolved yet, which fired on *every*
relaunch. The check is null-safe, the cursors are maintained on optimistic writes, the head row is
repaired when the head itself is removed, and a library that grew at the front is prepended rather
than rebuilt.

**A warm cache opens straight into the library.** The first-login bootstrap splash was decided by
reading the library UI state, which is empty on every cold start by construction, so it showed even
when everything was already on disk — and then waited out its own timeout. It asks the database now,
and each tab seeds from Room before the network check instead of flashing a loading state.

**Pinning an album no longer looks like a scraper.** Downloads waited 400–1200 ms between tracks and
had no idea what a 429 was: three fast retries into the same limit and the row went red. The gap is
2.5–5 s jittered now, and a detected rate limit backs off 20 seconds for up to eight attempts before
giving up. Upstream's research note ships with it — the surface that throttles pins is the audio CDN
and the resolve that mints its URL, not the Web API, which is why a `Retry-After` was never the
signal to look for. There is no user-facing delay setting on purpose: what a zero costs is not your
download speed, it is your account.

**A slow chunk is no longer a failed download.** Most download failures are a dropped session that
works on the next attempt, so a non-429 failure now backs off and re-queues up to three times. The
RETRY button is for the ones that are really stuck, and a failure report is about a real failure
rather than about a phone walking past a lift.

**Artist pages have singles.** Fifty releases were requested and `take(50)` was applied to albums and
singles *concatenated*, so any artist with fifty albums showed no singles at all. They are fetched
separately now, two hundred each, with their own section — and EPs are labeled as singles instead of
falling through to "album". The page also says "Loading artist…" instead of going blank while the
fetch runs.

**Nothing tears the player down under audio that needs no network.** Our rule covered downloaded
tracks; it did not cover a streamed track already banked to its end, which needs the network just as
little. A Wi-Fi handoff mid-song was an audible pause-then-play for no benefit. Both cases defer now —
and they defer rather than drop, so the session rebuild is still owed and still runs at the next pause.

**And the handoff that fires it was dead code.** Confirming a transport change needs two samples, but
the first sample promoted the very field the second one is compared against, so the count could never
reach two — the path has never fired since it was written. The Wi-Fi prefer gate had the same problem
from the other side: it delivers exactly one callback, which can never confirm anything by itself.
Both fixed, which is only safe *because* of the banked-track rule above.

**A reconnect scheduled while the session looked dead is dropped once it recovers.** Position reports
resuming means the session is alive, and firing a stale reconnect six seconds later tore down a
healthy one — on our fork that also blocks the lock a play needs.

**A dead player thread is no longer mistaken for a working one.** Readiness asked only whether the
*session* was alive. With the player thread finished, commands were logged and dropped, `play`
returned success, and the app waited forever on audio that was never coming. That now forces a
rebuild.

**Look-ahead waits for the current track.** "Bank this track, then prefetch the next" was two
fire-and-forget calls racing each other for whatever bandwidth there was. It is sequential now, with
a 25-second ceiling so a stalled fetch cannot hold prefetch off forever.

**Smaller things.** Repeat-one wears a "1" so you can tell it from repeat-all. Toggling repeat
refreshes the queue and re-points the prefetch. A resumed download keeps its real duration instead of
completing as 0:00. The reported position can no longer be dragged below reality by a bad
pending-output reading.

**What we did not take.** Upstream's Media3 `StreamBanker`, `CdnUrlRefresher` and `QualityPolicy`
serve the TIDAL backend, which this fork does not have. Their `DownloadPlaybackQueue` solves a problem
our Downloads screen already solves with less machinery. Their `force_reconnect_check` returns early
and forgets the rebuild where ours defers and owes it. Their AudioTrack flush rebase and their
queue-snapshot-before-teardown are both things we had already arrived at independently, and ours are
stronger. Their Wi-Fi prefer gate is 30 seconds; ours stays at two minutes, which is the value this
fork's subway testing was built on. Their extracted `ReconnectPolicy` and `StreamingPolicy` refactors
are behaviour-neutral, and taking the second would have quietly dragged the 30-second gate in with it.

## BrightMusic v0.62 — offline says it once, and downloads play

**Downloaded music plays with no connection.** Four separate faults could each stop it, and every one
of them ended in the same picture: a spinner over a file sitting on disk.

*The engine believed it was online.* Its connectivity flag defaults to online and only one thing ever
corrected it — attaching the playback backend. An engine built by the download service instead never
got that call, so in airplane mode it thought it had internet, and being wrong there switches off
every rescue the offline path has: the downloaded-file fast path, the pin gate on queue advancement,
the handover when the buffer runs dry. Connectivity is pushed in when the engine is *born* now, and
the pushes reach an engine that has no backend attached yet.

*`onAvailable` claimed the network worked.* A network attaching is not a network carrying data, and
that callback wrote "online" flat out — for airplane mode with Wi-Fi on, for a captive portal, for a
cellular radio registered with nothing behind it. It also wrote asynchronously while the honest
answer writes synchronously, so the correct `false` could land first and then be overwritten by a
stale `true` that nothing came back to fix. The capabilities are read and believed instead.

*The session warm-up ran on the main thread.* No dispatcher, a blocking call across the FFI boundary,
and a 15-second timeout that cannot cancel a blocking frame. Offline the app opens on Downloads at
exactly that moment, so the first tap landed on a frozen UI thread and was never delivered — which
looks precisely like a tap that did nothing. It runs off the main thread now.

*And a reconnect could get in front of the tap.* Offline, the app still asked the engine to rebuild
its session, and that attempt blocks inside a retrying access-point connect while holding the same
lock a play needs. There is nothing to reconnect to with no network, so it no longer asks.

**Nothing takes the player out from under a pinned track.** The reconnect monitor tore the player
down whenever the session went invalid and the app was in the foreground — mid-song, over a file that
needed no network at all. It defers now, like every other rebuild path already did. A staged rebuild
(which a streaming-quality change in Settings triggers) used to tear the Active down and then only
try to build a *connected* replacement, so with no signal it left the engine holding nothing: an
offline player is the floor now, not a special case.

**A dead player thread is no longer mistaken for a working one.** Readiness only asked whether the
*session* was alive. With the player thread finished, commands were logged and dropped, `play` still
reported success, and the app waited forever on audio that was never coming. That state now forces a
rebuild instead.

**Offline says it once.** It used to say it twice, in two voices and two colours: a red banner over
the content reading "Not available offline." and a strip above the tab bar reading "Device offline".
The strip is the one that stays — it sits at the edge instead of pushing the screen down — and it
absorbs the only thing the banner added. It reads "Offline", or "Offline · not downloaded" when that
is the specific problem. Library screens with nothing cached now say what *is* available rather than
repeating the diagnosis.

**AirPlay stops panicking about being on a different Wi-Fi.** A speaker bridge lives on one network;
a phone travels. Failing to reach it is the normal state of things away from home, not a fault, and
it was reported as `Bridge error: failed to connect to 192.168.68.59 (url: …)` — a stack trace shown
to someone standing in a different room. Unreachable now means the Speaker Bridge section is simply
absent, along with the stale list of rooms you cannot reach, and Settings says "Not on this network"
rather than "Not reachable".

**Casting cannot swallow a tap offline.** Ownership of a Connect device can only be released over the
network, so a session that was casting when the signal died stayed "remote" forever — and every
command, including tapping a downloaded track, went to a speaker that could not be reached while the
local player stayed paused. With no connection, transport is the phone's.

## BrightMusic v0.61 — swipe from the left is a setting

Settings → Gestures → "Swipe in from the left to go back", on out of the box. BrightControl can put
its own strip down that same edge for every app, and two gestures with one owner between them is a
swipe that works or doesn't depending on where a thumb lands.

## BrightMusic v0.60 — downloads stop waiting for the play button

**Downloads no longer stall until you press play.** The report was exact: a download stops, you hit
play and it moves again, then it stops again. That shape is the whole diagnosis, because what play
does that nothing else does is put a foreground service in front of the process.

Everything kicks the download service — an enqueue, a collection, the pin audit, the podcast and
library auto-downloaders, and the app itself on open. Each of those starts used to launch its own
drain loop over the same queue, which was safe on its own (one mutex, one transfer at a time), and
each loop also owned the teardown. So the loop that found nothing left to take — because another loop
was already holding the mutex and downloading the last track — fell into its `finally` and called
`stopForeground(REMOVE)` and `stopSelf` **on top of a transfer still in flight.**

What that costs is not the notification. It is the process. With no foreground component the app is
a cached process, and a cached process gets frozen and Doze-throttled: the transfer stops where it
stands, with nothing to retry, no error, and a row that still looks fine. Press play and the media
service raises the process back up and the parked transfer carries on. Pause, and it goes back down.

The drain is single-flight now, and only the drain may tear the service down. Extra starts re-post
the notification, mark that work is waiting, and return. A kick that lands in the gap between the
last empty check and the release of the guard is picked up rather than lost — that window is where a
naive guard would strand a track forever.

**And the app was hitting that path constantly.** `MainActivity` called `resumeDownloads` from inside
the composable body rather than from an effect, so a `startForegroundService` fired on every single
recomposition of the root. Hundreds of starts, hundreds of loops racing each other's teardown. It is
keyed to the controller now, which is what "resume on app open" always meant.

**A foreground service keeps the process alive, not awake.** Separate cause, same symptom, and worth
stating plainly because this project has learned it before. Being a foreground service exempts you
from being killed; it does not hold the CPU up. With the screen off the device suspends and a blocking
chunk fetch makes no progress — and does not even time out, because the wait is measured in monotonic
time, which does not advance across suspend. The drain now holds a partial wake lock and a Wi-Fi lock
for exactly as long as it runs, both released in the same `finally`, the wake lock renewed on a
timeout it can never outlive by accident. A leaked one here is a flat battery, so the timeout is the
safety net and the `finally` is the intent.

**A slow chunk is no longer a failed track.** librespot waits eight seconds for a fetch, derived from
its own minimum-throughput floor, so a 256 KiB chunk has to average 32 KB/s or it comes back as a
timeout. That timeout used to be handed straight up as a hard error, killing the entire download;
three of them and the row went FAILED. On a phone that is a normal Tuesday — an elevator, a platform,
two bars of cellular. A timed-out chunk is retried in place now, about a minute of patience per
chunk, with the ten-minute deadline on the whole file left as the real bound.

**If the service is taken down under the drain, the transfer ends with it.** Its coroutine scope was
never cancelled, so a `stopSelf` from one of those duplicate loops left the download running in a
process with nothing holding it up — alive on paper, frozen in practice, and invisible.

## BrightMusic v0.59 — a dot for what you have not heard, and colour that stops flickering

**Unheard episodes are marked.** A dot at the end of the row on every episode you have never
started, on both the show screen and Saved Episodes. Start one and the dot goes: the row already says
"22 min left" on the line below, and two answers to one question is worse than one. Finish it, or
long-press to mark it played, and it goes for good. Nothing unplayable is ever dotted — the row is
greyed because tapping it does nothing, and a dot inviting you to hear something unhearable is worse
than no mark at all.

**A dot on the show, too, so you can see it without opening anything.** Which needed the phone to
know something about feeds it is not looking at, and it only knew that for shows with auto-download
on — which most followed shows do not have. So the daily check now walks every followed show and
asks one question of each: what is the newest episode? One page of one, once a day, riding the alarm
that was already there. A show whose newest episode you have not started gets the dot.

It is deliberately not a count. A number would mean paging entire back catalogues on a schedule, and
the question a shows list is being asked is "is there anything new here", which the newest episode
answers by itself. A show followed since the last probe has no mark until the next one, rather than a
blank pretending to mean "nothing new".

**Colour is held for the whole app now, not just around a cover.** The old behaviour was correct on
its own terms — everything this app draws besides a cover is greyscale anyway — but not stable:
leaving a cover wrote greyscale back, BrightControl's per-app colour rule wrote colour again the
moment it saw the setting move, and the two took turns on every scroll. Two writers is the problem,
not either write. This app now states one thing while it is in front, BrightControl v3.36 states the
same thing for this package, and agreement is what makes a second writer harmless. Pick DITHER or
GREY under artwork and the panel stays mono, as before — that is a request for a mono phone and this
is not the place to overrule it.

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
