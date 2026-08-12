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
