# librespot-audio 0.8.0 patches (phono)

## Mobile CDN resilience

- `download_slots`: `Semaphore::new(2)` (was 1) — pipeline two concurrent range fetches
- HTTP 429: drop download permit before `Retry-After` sleep, re-acquire after
- Transient non-206 responses: retry up to 3 times with backoff before surfacing to decoder
- Export `Range` from crate root for playback patch

## Load trace

- `AudioFile::open` notes a cache hit; `AudioFileStreaming::open` notes storage-resolve (time, URL
  count, failure) and each CDN attempt by host with its HTTP status or error
- `receive.rs` notes non-206 answers and failed ranges (host, status), the first bytes in, the
  download finishing, and the download being closed or dropped
- `AudioFileStreaming::read` notes a read that gave up waiting for bytes
- Hosts only, never a full URL (`phono_trace::url_host`)

Upstream: crates.io `librespot-audio-0.8.0`
