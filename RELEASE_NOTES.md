## A sleep timer, and a fade between tracks

### Sleep timer

Now Playing has a line under the transport that says "Sleep timer", and tapping it offers 15, 30,
45, 60 or 90 minutes, or the end of whatever is playing — "End of episode" on a podcast, which is
the one people actually reach for. A running timer replaces that line with what is left of it, so
the answer to "how long have I got" is on the screen you are already looking at rather than three
taps into Settings. Cancel and "add 15 minutes" are on the same screen.

**It fades out over the last twenty seconds instead of stopping.** That is the whole feature. A
hard stop is a change in the room and it wakes people up, which defeats the point of setting one.
"End of track" is the exception: it was asked to let the track finish, and a track that finishes
has already ended on its own terms.

Two things made this harder than it looks, and both are worth knowing if you are wondering why a
music app needed an alarm to turn itself off.

A foreground service keeps a process **alive**, not **awake**. By the time a 45-minute timer
expires the screen has been off for three quarters of an hour and the phone is in Doze: a
coroutine waiting on a delay is not running, and neither is a `postDelayed`. Only an
allow-while-idle alarm fires through that, so that is what the timer is.

And it is *one* alarm, not two. The obvious build sets one alarm to start the fade and another to
stop the music. Android rations allow-while-idle alarms to roughly one per app every nine to
fifteen minutes, so the second alarm would not have been twenty seconds late, it would have been a
quarter of an hour late — the music stopping long after it had already gone quiet. There is one
alarm, set for twenty seconds before the deadline, and the fade and the stop both happen in the
process it wakes, under a wake lock so the CPU does not drop back down between steps.

The timer works on radio as well as on Spotify, since falling asleep to a station is at least as
common as falling asleep to an album. It is cleared if playback ends some other way first, so a
timer set and forgotten cannot reach out and pause something tomorrow.

### Gapless

Already there, on, and now checked rather than assumed. The Settings toggle has been wired to
librespot's gapless mode since this fork began: one player, the next track preloaded about thirty
seconds early, and the audio sink never stopped between the two. Nothing is torn down and rebuilt
at a track boundary, which is what a live album or a DJ mix needs. If you have been hearing gaps
in one, the cause is elsewhere — a buffer underrun on bad signal will do it — and is worth
reporting.

This applies to Spotify audio, whether streamed or played from a download, and to podcasts. Radio
has no track boundary to be gapless across.

### Fade between tracks

New, in Settings, off by default: 2 to 12 seconds.

It is called a fade rather than a crossfade because that is what it is. A crossfade needs two
tracks decoding at the same instant, and this app has one librespot player feeding one audio
track — there is nowhere to put the second one, and a second decoder would mean a second Spotify
session. So the outgoing track fades down into the boundary and the incoming one fades up out of
it, half the length you pick on each side. On shuffled tracks it does the job people want a
crossfade for: songs stop arriving as a hard cut. Back to back on a fast transition you can hear
that the two do not overlap.

**It is off by default because it ruins a gapless album.** The two settings genuinely conflict and
there is no clever resolution, so the rule is: if a fade is set, the fade wins, on every track
change, including the ones an album was mixed to run through. What Settings shows is the honest
consequence — with a fade set, gapless is no longer a toggle, it reads "on, for the fade", because
the fade needs the tight seam gapless provides and the player keeps it on regardless. Two seconds
is enough to take the edge off a cut if you want some of this on an album.

Podcasts are left out: an episode plays on its own, so there is no transition to smooth, and the
only thing a fade would do is lose the first sentence. Radio is left out because a stream has no
track boundary at all. The last track of a queue plays its ending rather than fading into nothing.

### Not tested by anyone with headphones yet

The fade curves, the timings and the precedence rule are covered by unit tests, and the code paths
are the ones the app already used for its pause fade. What no test can tell you is whether a
6-second fade sounds right, whether twenty seconds is the correct length to fall asleep through,
or whether the seam is as tight in the room as it is on paper. If any of those are wrong, shake
the phone and say so.
