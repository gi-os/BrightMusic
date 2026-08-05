## LightPhono v0.11.0 — backups, and a smaller app that starts sooner

**Nothing about the app looks different. Two things underneath it changed, and one of them
carries real risk.**

### Your stations, podcasts and play history can now be backed up

LightSync can see this app. It picks up four things, each separately: the library database and
the pins on the home screen, the radio stations and podcast settings, two years of play history,
and the ordinary preferences — theme, artwork treatment, Connect speaker names, auto-download
rules, where you were in the last thing you played.

What it deliberately does not take is as much of the point. Downloaded audio and the streaming
cache are excluded: they are hundreds of megabytes that Spotify will hand back on request, and a
backup you cannot fit anywhere is not a backup. The Web API sign-in is excluded too, for a harder
reason — it is encrypted with a key that physically cannot leave the phone, so restoring the file
would restore something undecryptable and turn a re-login into a crash. Expect to sign in again
after a restore, and expect downloads to come back on demand rather than instantly.

The station list is the part worth protecting. A station added from radio-browser.info, a podcast
subscription, an episode you were halfway through — none of that exists anywhere but this phone.

### The release build is minified now, for the first time ever

Every LightPhono release until this one shipped unshrunk. The rules for shrinking it existed but
had never been run, and this app is a bad candidate: JNA reflection, UniFFI bindings, JNI
callbacks arriving from a Rust thread, Room's generated code found by name, bundled ML Kit. Any
one of those loses a class it needs and the app crashes at the moment you touch that feature,
not at build time.

So the rules were written one mechanism at a time, each with a note saying what would break
without it, rather than by keeping everything and calling it done. R8's full mode is on as well,
which is where most of the cold-start win comes from — and on this phone, cold start is the thing
you actually feel.

The download is a third of what it was: 75.8 MB to 24.1 MB. That was not the goal and it is more
than expected — the assumption had been that an APK this full of native libraries and an ML Kit
model had little left to shrink. Most of it is unused resources and the dex the shrinker could
finally see was dead.

**Be honest about this: it has not been smoke-tested on a device.** CI proves it builds and that
the unit tests pass; it cannot prove that ML Kit still decodes a QR code or that the Rust player
thread still finds the audio sink. If something that worked in v0.10 fails in an obvious,
feature-shaped way here — the QR scanner on Web API setup, playback going silent, a setting
resetting itself — that is the likely cause, and rolling back to the previous release is the
right move while it gets fixed.

### Also

The wheel code moved out of this repo and into the shared `light-common` library. Same behaviour,
same feel — the file was identical to the library's copy in everything that matters — but it is
now maintained in one place across the Light apps instead of nine. Shake-to-report stays local:
it takes a screenshot, and the shared version cannot do that yet.

One consequence for anyone building from a checkout: `light-common` lives in GitHub Packages,
which requires a token even for public packages, so `local.properties` now needs `gpr.user` and
`gpr.key`. See the README.
