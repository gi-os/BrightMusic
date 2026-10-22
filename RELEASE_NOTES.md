## BrightMusic v0.84: a restored song resumes again

**Tapping play on the song the player offers to resume now starts it from where it stopped.**

A report said playback sat loading and never started. The playing screen was offering to resume a
song the user had stopped part way through, and the song was downloaded on the phone. The user
tapped play. The spinner came up, no audio came, and after 10 seconds the stall watchdog filed the
report.

Resuming used to happen in two steps: load the song, then jump to the saved position once the song
looked loaded. To tell when the song was loaded, the app watched for the song and its length to
show up. But the app fills in both of those the moment play is tapped, before the player has
loaded anything. So the jump fired straight away, and starting a jump cancels whatever play
request is still in progress. It cancelled the load itself. The player was told to jump while it
was stopped, the song was never handed to the engine, and the spinner stayed up. The watchdog
then tried to switch to the downloaded copy, but the engine had nothing queued, so it found
nothing to play.

Now the saved position goes into the load itself, the same way episodes already resume. The song
loads and starts at that position in one step. There is no separate jump afterwards, so nothing
can cancel the play.

Fixes [light-reports#752]: playback sat loading and never started when a restored song was resumed from the playing screen.
