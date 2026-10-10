## BrightMusic v0.81 — the stall report shows where playback stopped

**A song that sits loading and never starts now files a report with every step of the load.**

The phone files its own report when the spinner stays up for ten seconds with no audio. These
reports came in from v0.72 through v0.80, and each one said the same thing. The network was online.
The engine held a live session. The play call returned. No player event arrived, and no audio
played. All of it was true, and none of it said where the load stopped.

The report now carries a player trace. The app and the native engine write each step of a load to
one short log. Every line has its time in seconds from the play request. The trace records these
steps.

- The play request, the wait for the transport lock, and the play call into the engine.
- The session check, any session rebuild, and whether the player thread is alive for the load.
- The player thread taking the load, the sink flush, and each player event it sends.
- The loader steps: metadata, format, file open, audio key, decoder, and seek, each with a time.
- Storage-resolve and each CDN request by host, with its HTTP status, first bytes, errors, and
  cancels.
- The first decoded audio, the first PCM the AudioTrack takes, and the first move of its playhead.
- Sink errors, audio focus changes, network changes, and each change to the loading and playing
  flags.

The last lines show the state when the report goes out: the engine, the sink, and the transport
lock. A URL keeps only its host, so no access token goes to the tracker. Each fault family still
files once per build, so v0.81 files a fresh report for each family. Playback behavior does not
change. This release only adds the log.

Refs [light-reports#671] -- playback sat loading and never started; the report now carries the player's event trace.
