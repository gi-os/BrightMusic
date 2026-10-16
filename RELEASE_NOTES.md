## BrightMusic v0.82: a paused song stays paused

**A song you pause after a resume no longer gets restarted behind your back, or reported as stuck.**

A report from the liked screen said playback sat loading and never started. The player trace in
that report showed something else. The audio did start. The AudioTrack took its first PCM, the
app showed playing at 0.14 seconds, and the playhead was moving by 0.56 seconds.

The resume call returned a moment after the player had already said it was playing. The app then
set the loading flag again, and put the spinner back on over live audio. The player answered the
repeated resume with "play while playing" and sent no new event, so nothing cleared the flag. At
2.6 seconds the user paused. A pause did not clear the loading flag either. At 10.8 seconds the
stall watchdog saw a spinner with no audio. Its rule for paused tracks only covered buffering, not
loading, so it handed the paused track to downloaded audio, which loaded the song to play against
the pause. Then it filed a report that said the load was stuck.

Three things change.

- Resume only puts the spinner up when the player is not already playing.
- A pause clears the loading and buffering flags, both when you pause and when the player reports
  a pause on its own.
- The watchdog never acts on a track you paused, whatever flag is left over. It takes the spinner
  down and waits. Nothing was asked to play, so there is nothing to hand off and nothing to report.

Fixes [light-reports#732]: playback sat loading and never started after a resume and pause on the liked screen.
