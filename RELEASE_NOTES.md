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
