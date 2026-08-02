## Phono v0.4 — downloads you can repair, and an honest answer about Serial

**Failed downloads retry on a tap, Downloads finally shows artwork, a podcast can fetch its next
three episodes from either screen, and nothing already on the phone gets pruned by retention any
more.**

**Downloads were disappearing.** Retention defaults to "Keep 3" and trims a show back to that limit
whenever auto-download runs — including the run that fires the moment you switch auto-download *on*.
Turn it on for a show you had already downloaded ten episodes of and the app fetched two more, then
deleted nine. v0.3 stopped counting hand-picked episodes towards the limit, but only for episodes
picked after v0.3 existed; everything already on the phone still looked automatic. So the first check
after this update grandfathers all of it: every episode currently pinned is treated as chosen by
hand, automatic or not, and retention governs only what arrives from here on. The limit still works,
it just cannot reach backwards any more.

**Retry.** A download got three automatic attempts and then became a dead end — the row said
"Failed" and there was nothing to press. Most of these are a dropped session or a CDN that timed out,
so the second attempt tends to just work. Tap a failed row to requeue it, or RETRY FAILED to requeue
a whole collection. The attempt counter is cleared as well, otherwise a track that had already burned
its three would fail again the instant the queue reached it and the button would look broken rather
than merely unlucky.

**Artwork.** Downloads was the only list in the app without covers, which made it the hardest one to
scan, and the images were already on disk beside the audio — nothing to fetch, nothing saved by
leaving them out. Podcast collections also stop calling themselves "Album", which is what a subtitle
written before shows existed says.

**Next three.** DOWNLOAD NEXT 3 on the episode list and on the podcast's page in Downloads. "Next"
follows the order the list is reading, so it means the newest three by default and the next three
chronologically when the list is oldest-first — which is the point, for anyone working through a back
catalogue. Episodes already on the phone are skipped rather than counted.

**Sort and select scroll away.** They were pinned above the list, spending a row of height on every
screenful of something you mostly scroll, to offer two controls you press once. They now sit inside
the list under the cover.

**Serial, and shows like it.** Some podcasts are not hosted on Spotify's servers at all — Spotify's
own client streams them over plain HTTP from the publisher's feed. librespot can only ask Spotify for
an audio key and a file id, and for those episodes there is no file id to ask for, so the download
came back "no playable file" and playback loaded nothing and sat there. Neither said why. They are
now greyed and labelled "Not on Spotify's servers", from Spotify's own `is_externally_hosted` flag
where it is set honestly, and otherwise from a download that already discovered it — the reason is
remembered per episode, because `downloaded_tracks` has no column for it and adding one would mean a
Room version bump, which this database answers by deleting every download to explain why one of them
failed. This is a limit of the playback core rather than a bug: those episodes cannot play here, and
saying so is the fix.
