## LightPhono v0.10.0 — it stops closing itself on open

**Opening the app could kill it a few seconds later, on the home screen, having played nothing.**
The crash was `ForegroundServiceDidNotStartInTimeException`: Android gives a service five seconds to
post a notification after `startForegroundService`, and this one did not.

Every path that needs the playback engine — including the session warm that runs on open, before you
have asked for any audio — starts the service to get at it. The service answers that by posting a
placeholder notification, "Starting playback…", which Media3 then replaces once it has a real session
to show. That placeholder was posted only while no session existed yet, so that it could never sit in
the shade next to the real media notification.

That guard was right about the notification and wrong about the deadline. When playback pauses, Media3
calls `stopForeground`, which drops the service out of the foreground while leaving the session built.
From that moment the service had `foregroundStarted = false` and a non-null session, which is the one
combination neither promotion path allowed — so the next warm-on-open called
`startForegroundService`, nothing posted, and five seconds later the process was killed. The first
launch after installing was fine. The first launch after ever having paused was not.

The deadline is now answered unconditionally, and the duplicate notification is prevented from the
other end: if a session already exists and nothing is playing or loading, the service posts, stands
down again immediately and removes the placeholder. A start like that was somebody warming the engine,
not asking for audio, so there is nothing for a foreground service to be in the foreground for. Media3
promotes it back the moment you press play.

Fixes [light-reports#9] — "it closed itself" on the home screen. [light-reports#5] and
[light-reports#6] are the same crash reported against v0.3.
