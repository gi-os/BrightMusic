## LightPhono v0.9.0 — podcasts resume, the signal can come and go, and loading looks like loading

**Episodes pick up where you left them, walking back into signal no longer kills the audio, and the
play button stops pretending to be idle when it is working.**

**Podcasts resume.** Two separate faults, one of them mine from last release.

The position was being applied by seeking *after* playback started, and that seek was racing the
load — it fired before anything was loaded, cancelled the load on its way past, and then got wiped
when the track finally reported in. The bar would jump to where you were and fall straight back to
0:00. The guard meant to prevent exactly this stopped working the moment episodes gained a duration in
v0.8.0, because it waited for a duration the app now fills in optimistically before the engine has
done anything. The position is handed to the loader now, so there is nothing to race.

It also wasn't being *saved* reliably. It was written when you pressed pause in the app, when the
episode changed, and when the app shut down cleanly — which misses the ways listening actually stops:
pausing from the lock screen or a headset button (that never reaches the app's own pause), the process
being killed, or swiping the app away. It now saves every ten seconds while playing. And a bug worth
naming: the "you finished this one" check, which deletes the position so you don't resume into the
credits, was comparing against episode lengths that were being under-reported for downloads — so past
about a third of the way through, every save *deleted* your position instead of storing it.

Downloads had no resume at all, separately — playing a downloaded episode from the Downloads screen
always started at zero by construction. Every route now goes through the same place, so the show
screen, Saved Episodes and Downloads all behave the same.

**Losing signal, getting it back, and losing it again.** The handover to downloaded audio worked, and
then walking back into coverage destroyed it. Reconnecting tears the player down to build a fresh
session — which is fine, except it was doing that *while audio was playing*, and without saving the
queue first. So the new session came up with nothing in it: silence, no way to skip out of it, and
every later press of play doing nothing at all, because there was nothing loaded to un-pause. That is
the "can't press play again" part, and it needed the app restarted.

Three changes. A player that is making sound is left alone — the session gets rebuilt when the track
ends or you pause, not while you are listening to it. Reconnecting saves the queue before it tears
anything down. And pressing play on a player with nothing loaded now loads the track instead of
quietly doing nothing, which is the same fix the radio got in v0.7.

**Loading looks like loading.** The play button was one static glyph, so "fetching audio" and "you
pressed it and nothing happened" were the same picture. There is a ring around it now while it is
working. A load that never finishes is also given up on after twenty seconds instead of leaving the
button stuck looking dead forever — which had the nasty side effect of switching off the very
watchdog that would have recovered it.

Both rules that decide this stuff — when a stall becomes downloaded audio, and when a position is
worth keeping — have tests now. Between them they account for most of this list.

---

## LightPhono v0.8.1 — the real fix: music keeps playing when the signal dies

**v0.8.0 fixed the wrong half of this. When you walk into a dead zone, playback now continues from
the downloaded copy instead of stopping when the buffer runs out.**

The symptom was never "a download refuses to play". It was: music is streaming, the signal goes, the
read-ahead runs dry, and the audio just stops — with a downloaded copy of that exact track sitting on
the phone.

Everything that rescues playback at that moment — the stall watchdog's handover, and the audio
engine's own recovery when it reports a stop — was gated on one flag meaning "the connection is
gone". **Nothing ever set that flag in a dead zone.** Android only tells an app a network was *lost*
when it disappears, and in a dead zone the radio stays registered and attached; it simply stops
carrying data. The one callback that does fire never touched the flag. So it stayed "online" for as
long as the app was running, the handover never ran, and playback stopped.

v0.8.0 corrected how that flag is *computed* — asking whether traffic actually reached the internet
rather than whether a network was merely attached — but left it being computed only when the app
starts. Fixing the question without asking it at the moment the answer changes fixed nothing. It is
now recomputed whenever connectivity changes, which is the signal a dead zone does send.

**And the rescue no longer depends on being told at all.** After fifteen seconds of silence the
player falls back to a downloaded copy regardless of what the connectivity flag claims — because
audio that has been dry that long *is* the evidence, and some phones are slow to admit a connection
has stopped working. If there is a downloaded copy, it plays. If there isn't, nothing changes and it
keeps waiting, because a long stall on genuinely slow data is still worth waiting out and telling you
your library is unavailable would be wrong. The engine does the same thing on a stop event now: a
downloaded copy of the current track has one right answer, and it does not depend on a flag.

The rule that decides all of this has a test now. It has been wrong twice.

**Also:** opening the app in a dead zone left it with no audio player at all, because startup only
knew how to build one by connecting to Spotify first. It now falls back to an offline player, so the
first thing you tap does not have to build one from scratch.

---

## LightPhono v0.8.0 — downloads play with no signal, and podcasts get a real progress bar

**The offline library works offline, an episode shows how far into it you are and can be scrubbed,
and podcasts can be saved.**

**Downloads play with no internet.** They were supposed to already. The problem was one predicate:
the app asked Android whether a network was *attached* rather than whether it actually *worked*. A
cellular radio that is registered but carrying no data, a hotel captive portal, a subway platform —
all of them report a network with internet capability, and none of them have internet. Believing it,
the engine skipped the downloaded-file fast path, never handed off to local audio, and instead tried
to rebuild the Spotify session: it tore down the player that could have played the file and then sat
in a retrying access-point connect. A spinner over audio already on the phone.

Three things changed. The check now asks for *validated* connectivity, which is the platform's
finding that traffic reached the internet rather than the transport's claim about itself. More
importantly, a downloaded file is now checked **first**, before anything about the network is
consulted at all — it needs no access point, no audio key and no CDN, so it no longer waits on one,
and if there is no player at hand an offline one is built rather than a connected one. And the
access-point connect is finally bounded: its five-second timeout was only ever covering the
handshake, because of the way the code was written the TCP connect and its blocking DNS lookup sat
outside it. On a dead network that turned one reconnect into minutes.

Also fixed: about thirty seconds before the end of a downloaded track, the app would start fetching
the *next* one over the network even with no connection, competing for the loader with the track
playing fine off disk.

**Podcasts have a working progress bar, times, and scrubbing.** All one bug. An episode's length had
exactly one source — a metadata call that fetched `/tracks/{id}` regardless of what it was asked
about. For an episode that is a 404, swallowed silently, so the length came back as zero. Zero length
is what hid the bar, both time labels, the drag-to-scrub gesture *and* the ±15s buttons, since all
four are gated on knowing how long the thing is. The screen was right all along; it was being told
the episode was zero seconds long.

Episodes now have their own metadata call, and a downloaded episode reads its length off the
downloads table before reaching for the network at all — so the bar works on a plane. Drag anywhere
on it to scrub, and the elapsed and total times sit underneath.

A downloaded episode's length was also wrong in the audio engine, which estimated it from the file
size assuming music bitrates. Podcasts are encoded much lower, so a pinned episode reported roughly a
third of its real length. It now reads the actual length out of the file.

**Podcasts can be saved.** The + on the player works on an episode. It always sent the right request
— and then reported "Could not load track metadata after save" on a save that had already succeeded,
because of the same missing episode lookup. Saved episodes get their own list, at the top of the
Podcasts tab: hold a row there to unsave, tap to play, and it picks up where you left off. On a
podcast the filled button means "saved, tap to unsave" rather than "add to a playlist", because an
episode cannot go in one.

Saved episodes are kept out of Liked Songs deliberately — an episode sitting among the songs would be
wrong — but they are saved to your Spotify account, so they show up in Spotify's own apps too.

---

## LightPhono v0.7.0 — local radio, and three fixes to playing on someone else's speaker

**The Radio tab searches every internet station there is, WNYU included, and Spotify Connect stops
playing in two rooms at once.**

**Radio is not just NTS any more.** The tab opens on a list of New York stations — WNYU, WNYC, WKCR,
WFMU, WBGO, WQXR, WBAI — with the NTS channels and mixtapes underneath. A **Find** field above them
searches [radio-browser.info](https://www.radio-browser.info), a community directory of roughly fifty
thousand stations, so anything with a public stream is two taps away whether or not it is in New York.
Tapping a result saves it and starts playing it; holding one saves it without committing to listening,
and holding a saved station removes it. No account, no key, nothing to sign into.

The New York list is seeded once on first run and then belongs to you: remove a station and it stays
removed. Saved stations live in preferences rather than the database, deliberately — the music library
uses destructive migrations, so adding a table for seven radio stations would risk your downloads.

Stations report what is on air where their server will say. NTS has its own API for that. For everyone
else the title comes from Icecast's `status-json.xsl`, which is not something every station exposes —
`MediaPlayer` has no way to read the title the stream is already carrying in-band, so it has to be
asked for separately, and a station that will not answer simply shows its own name. It still plays.

**Radio comes back after a dropout.** Losing signal used to end a stream permanently: the play button
would do nothing for the rest of the session and the only way back was to pick the station again. A
`MediaPlayer` that hit an error, or that reached "completion" — which for a live stream means the
socket closed, not that anything ended — cannot be started again, and the old code called `start()` on
it anyway and swallowed the failure. Pressing play now opens the stream afresh. It also recovers on its
own: a dropped community station gets three quiet reconnect attempts over about six seconds before you
see an error at all. NTS is excluded from that, because its relays do not drop and hiding it would hide
a real problem.

**Connect no longer plays in two places at once.** Handing off to a speaker paused this phone *after*
the speaker had already started, so both played for as long as the round trip took — and if the reply
never came back, the phone never paused at all and both played indefinitely. The phone is now silenced
first, and the pause is undone if the handoff fails. Hopping speaker to speaker explicitly stops the
one you are leaving instead of trusting it to notice, which a ZeroConf-claimed receiver frequently does
not.

**Connect no longer loses track of the speaker it is driving.** Opening "Play on" mid-session could
quietly drop the app back to local while the speaker played on — after which every transport button and
every setting silently did nothing, because there was no device left to send them to. The cause was
treating one absence from Spotify's device list as proof the session had ended. It is not: a speaker
drops out of that list while it re-registers, and a claimed receiver can come back under a different
id. Absence now has to be corroborated by three consecutive lists *and* no playback state before
control is given up.

**And you can change a Connect device's volume.** A stepper on the "Play on" screen, under the device
list, while something remote is playing — a stepper and not a slider because this is a small screen
usually being operated with a thumb. A device that says it does not accept volume changes says so
instead of failing silently.

---

## Phono v0.5 — a download that tells you how far along it is, podcasts at your speed, and a library that fills itself

**Downloads show a percentage instead of a spinner, a tap on an episode changes the playback
speed, and Liked Songs and Daily Mixes can keep themselves on the phone overnight.**

**Progress.** A pin said "Downloading…" from the moment you asked for it until the moment it
finished, which on an hour-long episode is several minutes of a screen that looks identical whether
the transfer is moving or stuck. It now counts. The download core reports how many bytes it has
pulled once per chunk, the Downloads screen puts a percentage under the title, and the notification
in the shade carries a real bar.

Two smaller things fell out of it. Queued tracks now say "Queued" rather than "Downloading…" —
downloads run one at a time, so a forty-track album claiming forty simultaneous transfers was
describing something that was not happening. And the notification names the track it is actually
working on, with a count of what is behind it.

The percentage is of compressed bytes coming off Spotify's servers, so it is a hair off the size of
the finished file. It is a progress bar; it is not a receipt.

**Speed.** Podcasts play at 1x, 1.2x, 1.5x, 1.75x, 2x and 0.8x. The control is on the player where
shuffle sits for music — an episode loaded on its own has nothing to shuffle, so the slot was doing
nothing, the same reason the skip buttons became 15-second jumps. Tap to step through, and it stays
where you left it.

Pitch is preserved, so this is a voice talking faster rather than a voice talking higher. The rate
also survives the things that quietly rebuild audio output underneath you — switching to headphones,
a stall, a dropped connection — which is what would otherwise have put an episode back to 1x halfway
through without telling you. Music is left alone at 1x deliberately and has no control: an album is
mixed at a tempo.

The lock screen knows about the speed too. Its progress bar advances by guessing between updates from
the player, and a bar guessing at 1x under audio running at 1.5x crawls and then jumps.

**Filling itself.** Settings has a new section: keep Liked Songs offline, keep Daily Mixes offline.
Both are off unless you turn them on, and both check overnight on the alarm the podcast downloader
already uses — the point is that the music is there in the morning without you having thought about
it the night before.

Liked Songs keeps a window of the newest ones, 50 by default, adjustable. A window has to let go at
the far end or it is not a window, so liking new music eventually drops the oldest in the window —
except where a track is also part of an album or playlist you downloaded yourself, which is yours and
is left alone. A library of several thousand tracks is larger than this phone, which is why there is
a limit at all rather than a "download everything" switch.

Daily Mixes are compared by what is in them rather than by the date on them. Spotify regenerates a
mix whether or not it changed, and a mix that came back the same costs one metadata call and no
audio.
