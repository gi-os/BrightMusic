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
