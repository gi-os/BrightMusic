## Phono v0.3 — the whole podcast feed, in either direction

**Episode lists now scroll to the end of the show instead of stopping at fifty, read oldest-first if
you want them to, and SELECT turns the list into checkboxes so a batch of episodes goes offline in
one go.**

**Scrolling past fifty.** `/shows/{id}/episodes` and `/me/shows` both cap a page at fifty items, and
the screens rendered exactly one page each. A show's fifty-first episode and a fifty-first
subscription did not exist as far as the app was concerned — no error, no end-of-list marker, just a
list that stopped. Both now fetch the next page as the loaded edge comes into view, twelve rows
ahead rather than the sixty the library lists use: those pages come out of Room, these come off the
network, and a prefetch distance wider than a page means the screen fetches a second page before
anyone has scrolled at all. The scrollbar sizes itself against the feed's real length, so the thumb
says how far through a five-year archive you are rather than how far through what has been fetched.

**Oldest first.** Spotify's endpoint has one order, newest first, and no sort parameter. Reversing
what is loaded would have shown the newest fifty backwards and called it the beginning of the show,
which is worse than not offering it. So oldest-first is the same feed read from the other end:
display position `i` is API offset `total - 1 - i`, so a page of the oldest episodes is the window
at the far end of the feed, fetched and then reversed. That costs one request for the first
screenful, the same as newest-first, where sorting locally would have meant pulling down thousands
of episodes first. The arithmetic lives in `podcast/EpisodePaging.kt` with no Android imports and a
test that walks a whole feed to prove the pages tile it exactly once — an off-by-one there silently
skips or repeats episodes, which is not the kind of bug you notice. The setting is one toggle for
every show rather than one per show: it is a reading habit, and the per-show version is a preference
you would have to set again for every new subscription.

**Selecting episodes.** SELECT above the list turns every row into a checkbox and the header into
CANCEL / n SELECTED / DOWNLOAD; the batch goes down as a single collection write. Long-press still
downloads one episode, and is switched off while selecting so it cannot compete with the checkbox.

**The part that would have made this a trap.** Retention counts a show's downloaded episodes and
deletes back to the limit. Ticking twenty episodes of a show set to "Keep 3" would therefore have
downloaded all twenty and deleted seventeen at the next daily check — the app quietly undoing what
it had just been asked to do, hours later, with no way to tell what happened. Episodes chosen by
hand, by checkbox or by long-press, are now recorded per show and sit outside the rule entirely:
they neither count towards the limit nor get dropped. Retention governs what auto-download fetched
on its own; an episode you picked is an instruction.

Also fixed: the prune log line reported `rows.size - keep` as the number of episodes deleted, where
`rows` was already the list of episodes to delete — a negative number in logcat whenever it ran.
