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
