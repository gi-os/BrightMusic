## Phono v0.2 — shake the phone to report a glitch

**Shake Phono twice and a small SEND ERROR? chip appears in the corner; tap it and the phone files
a GitHub issue against the private tracker.**

The report carries the symptom, an optional note, the build and firmware, free space and heap, the
last crash log if there is one, and — only while the row stays ticked — a screenshot of the moment
you started shaking. Ported unchanged from gi-os/LightNotebook and gi-os/LightCamera, deliberately:
this is diagnostic UI rather than product surface, so it should be one learned gesture across every
app instead of three.

**One honest limit.** The accelerometer only runs while Phono is the app in front, so the moment
you are most likely to want to report something — a track that will not start while the phone is in
a pocket — is exactly the moment nothing is listening. That is what `report/Trouble` is for: a
failure the app catches offers itself for reporting without needing a gesture at all, and without
the app being on screen. Shake covers what looks wrong; Trouble covers what goes wrong.

The gesture is four reversals past 0.46g. It counts reversals rather than force, which is what
separates it from a phone being carried. Being wrong is meant to be cheap: the chip fades after
four seconds and deletes nothing.
