## BrightMusic v0.83: a finished song plays again

**Tapping play after the last song in the queue ends now starts that song again from the beginning.**

A report said playback sat loading and never started. A song had played to its end with nothing
queued after it. About a minute later the user tapped play. The spinner came up, no audio came,
and after 10 seconds the stall watchdog filed the report.

When the last song ends, the app moves its playhead back to 0 and shows the song as ready to play
again. The player underneath does not do the same. It keeps the finished song and stays at its
end. The engine treated the next tap on play as a plain un-pause, and the player answered "play
while at the end of a track" and did nothing. It sent no event back, so nothing ever cleared the
loading flag, and the spinner stayed up until the watchdog gave up.

Now, when the queue runs out, the engine moves its own position back to 0 and marks the song as
one that must be loaded again. The next tap on play loads the finished song fresh from the start,
the same way it already does when the player loses a song while it is paused.

Fixes [light-reports#745]: playback sat loading and never started when play was tapped after the last song had ended.
