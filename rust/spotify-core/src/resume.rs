//! Writing podcast positions back to Spotify.
//!
//! Spotify keeps podcast progress server-side, in what its own clients call the resumption
//! platform. The Web API exposes it read-only — `resume_point` on an episode, behind the
//! `user-read-playback-position` scope, which is where the inbound half of this feature comes
//! from — and there is no public endpoint that sets it. The write lives behind the herodotus gRPC
//! gateway on spclient, reachable with the Login5 identity this app already carries for playback:
//!
//! ```text
//! POST /herodotus/spotify.resumption.v1.ResumePointRevisionService/CreateResumePointRevision
//! ```
//!
//! Bodies are bare protobuf rather than gRPC-framed, so the request goes through
//! `SpClient::request` like every other spclient call. `user_id`, `resume_point_revision_id` and
//! `resume_point_id` are all filled in by the backend from the bearer token and the uri; Spotify's
//! own client leaves them empty too.
//!
//! The messages are hand-encoded rather than generated. `librespot-protocol` owns the generated
//! protobuf in this tree, and adding a service to it means patching another crate for two messages
//! of five fields between them; the wire format is pinned by the golden-bytes tests at the bottom
//! of this file.
//!
//! The part of `spotify/resumption/v1/resumption.proto` this uses:
//!
//! ```text
//! message ResumePoint {
//!   oneof resume_point {
//!     google.protobuf.Duration position = 2;
//!     google.protobuf.Empty finished = 4;
//!   }
//! }
//! message ResumePointRevision {
//!   ResumePoint snapshot = 2;
//!   google.protobuf.Timestamp create_time = 3;
//! }
//! message CreateResumePointRevisionRequest {
//!   string uri = 2;
//!   ResumePointRevision resume_point_revision = 4;
//! }
//! ```

use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use http::header::CONTENT_TYPE;
use http::{HeaderMap, HeaderValue, Method};
use librespot::core::Session;

use crate::SpotifyError;

const CREATE_RESUME_POINT_REVISION: &str =
    "/herodotus/spotify.resumption.v1.ResumePointRevisionService/CreateResumePointRevision";

/// Bound on a report. spclient's own strategy is ten attempts against an access point that may be
/// unreachable, and the caller is a phone that has just been paused — a report that cannot land in
/// ten seconds belongs in the pending queue, not holding a thread.
const REPORT_TIMEOUT: Duration = Duration::from_secs(10);

const EPISODE_PREFIX: &str = "spotify:episode:";

impl super::EngineShared {
    /// Tell Spotify how far into an episode playback got.
    ///
    /// Blocking: call it off the main thread. Returns `Ok` only when Spotify accepted the write.
    /// The caller uses that to decide whether it may record the value as "what Spotify now says",
    /// so claiming success on anything less would have the next list load undo local listening.
    pub fn report_episode_position(
        self: &Arc<Self>,
        uri: String,
        position_ms: i64,
    ) -> Result<(), SpotifyError> {
        self.post_resume_point(&uri, resume_point_position(position_ms))
    }

    /// Mark an episode listened to the end, so the next play starts it over rather than resuming a
    /// second short of the end.
    ///
    /// Blocking: call it off the main thread.
    pub fn report_episode_finished(self: &Arc<Self>, uri: String) -> Result<(), SpotifyError> {
        self.post_resume_point(&uri, resume_point_finished())
    }

    fn post_resume_point(self: &Arc<Self>, uri: &str, point: Vec<u8>) -> Result<(), SpotifyError> {
        if !is_episode_uri(uri) {
            return Err(SpotifyError::InvalidUri {
                uri: uri.to_string(),
            });
        }
        // Deliberately not `session_or_err` on its own: that builds a session when there is not
        // one, and a position report is not worth a connect attempt — in airplane mode it would
        // block the caller inside a retrying access-point connect while holding the lock a play
        // needs. With nothing connected the caller keeps the report queued instead.
        if !self.has_connected_active() {
            return Err(SpotifyError::NotLoggedIn);
        }
        let session = Self::session_or_err(self)?;
        let body = create_resume_point_revision_request(uri, &point, now_unix());
        let handle = self.runtime.handle().clone();
        handle.block_on(async move {
            match tokio::time::timeout(REPORT_TIMEOUT, post(&session, body)).await {
                Ok(result) => result.map_err(|e: librespot::core::Error| SpotifyError::Network {
                    msg: e.to_string(),
                }),
                Err(_) => Err(SpotifyError::Network {
                    msg: "resume point report timed out".to_string(),
                }),
            }
        })
    }
}

async fn post(session: &Session, body: Vec<u8>) -> Result<(), librespot::core::Error> {
    let mut headers = HeaderMap::new();
    headers.insert(
        CONTENT_TYPE,
        HeaderValue::from_static("application/x-protobuf"),
    );
    // A non-2xx comes back as an error from the http client, so an `Ok` here is Spotify having
    // taken the revision. The response body is the revision it stored, which says nothing the
    // caller does not already know, so it is dropped.
    session
        .spclient()
        .request(
            &Method::POST,
            CREATE_RESUME_POINT_REVISION,
            Some(headers),
            Some(body.as_slice()),
        )
        .await
        .map(|_| ())
}

fn is_episode_uri(uri: &str) -> bool {
    uri.starts_with(EPISODE_PREFIX) && uri.len() > EPISODE_PREFIX.len()
}

/// Seconds since the epoch. A clock set before 1970 would encode as a negative varint and is not
/// worth carrying the case for; the backend stamps its own update time regardless.
fn now_unix() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

// --- protobuf encoding ------------------------------------------------------------------------
//
// proto3: a field holding its type's default is not written at all. That is what makes a position
// under a second encode as an empty Duration rather than an explicit zero, and it is what the
// golden tests below pin.

const WIRE_VARINT: u64 = 0;
const WIRE_LEN: u64 = 2;

fn put_varint(out: &mut Vec<u8>, mut value: u64) {
    loop {
        let byte = (value & 0x7f) as u8;
        value >>= 7;
        if value == 0 {
            out.push(byte);
            return;
        }
        out.push(byte | 0x80);
    }
}

fn put_tag(out: &mut Vec<u8>, field: u64, wire: u64) {
    put_varint(out, (field << 3) | wire);
}

fn put_varint_field(out: &mut Vec<u8>, field: u64, value: u64) {
    if value == 0 {
        return;
    }
    put_tag(out, field, WIRE_VARINT);
    put_varint(out, value);
}

fn put_len_field(out: &mut Vec<u8>, field: u64, bytes: &[u8]) {
    put_tag(out, field, WIRE_LEN);
    put_varint(out, bytes.len() as u64);
    out.extend_from_slice(bytes);
}

/// `google.protobuf.Duration` and `google.protobuf.Timestamp` — the same two fields.
fn seconds_and_nanos(seconds: u64, nanos: u64) -> Vec<u8> {
    let mut out = Vec::new();
    put_varint_field(&mut out, 1, seconds);
    put_varint_field(&mut out, 2, nanos);
    out
}

/// `ResumePoint { position }`.
///
/// Rounded down to whole seconds, which is what Spotify's own client writes. That matters beyond
/// tidiness: the phone records what it sent as "what Spotify now reports", and a stored
/// millisecond that came back rounded would read as another device having moved the point.
fn resume_point_position(position_ms: i64) -> Vec<u8> {
    let seconds = (position_ms.max(0) as u64) / 1000;
    let mut out = Vec::new();
    put_len_field(&mut out, 2, &seconds_and_nanos(seconds, 0));
    out
}

/// `ResumePoint { finished }` — an `Empty`, so a zero-length message.
fn resume_point_finished() -> Vec<u8> {
    let mut out = Vec::new();
    put_len_field(&mut out, 4, &[]);
    out
}

fn create_resume_point_revision_request(uri: &str, point: &[u8], create_time_s: u64) -> Vec<u8> {
    let mut revision = Vec::new();
    put_len_field(&mut revision, 2, point);
    put_len_field(&mut revision, 3, &seconds_and_nanos(create_time_s, 0));

    let mut out = Vec::new();
    put_len_field(&mut out, 2, uri.as_bytes());
    put_len_field(&mut out, 4, &revision);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Golden bytes, cross-checked against a protoc-generated encoder for the same messages.
    #[test]
    fn a_position_request_matches_the_wire_format() {
        let body = create_resume_point_revision_request(
            "spotify:episode:abc",
            &resume_point_position(1_200_500),
            1_700_000_000,
        );
        let expected: Vec<u8> = vec![
            0x12, 0x13, // field 2 (uri), 19 bytes
            0x73, 0x70, 0x6f, 0x74, 0x69, 0x66, 0x79, 0x3a, 0x65, 0x70, 0x69, 0x73, 0x6f, 0x64,
            0x65, 0x3a, 0x61, 0x62, 0x63, // "spotify:episode:abc"
            0x22, 0x0f, // field 4 (resume_point_revision), 15 bytes
            0x12, 0x05, // field 2 (snapshot), 5 bytes
            0x12, 0x03, // field 2 (position), 3 bytes
            0x08, 0xb0, 0x09, // seconds = 1200
            0x1a, 0x06, // field 3 (create_time), 6 bytes
            0x08, 0x80, 0xe2, 0xcf, 0xaa, 0x06, // seconds = 1700000000
        ];
        assert_eq!(body, expected);
    }

    #[test]
    fn finished_is_an_empty_message_in_field_four() {
        let body = create_resume_point_revision_request(
            "spotify:episode:abc",
            &resume_point_finished(),
            1_700_000_000,
        );
        // The revision, snapshot and the `finished` empty, straight after the uri.
        assert_eq!(&body[21..27], &[0x22, 0x0c, 0x12, 0x02, 0x22, 0x00]);
    }

    #[test]
    fn a_position_under_a_second_encodes_an_empty_duration() {
        // proto3 omits a zero, so this is a Duration with no fields — what "resume at the very
        // beginning" looks like on the wire, and why the caller keeps a floor of its own.
        assert_eq!(resume_point_position(999), vec![0x12, 0x00]);
    }

    #[test]
    fn only_episodes_are_reportable() {
        assert!(is_episode_uri("spotify:episode:4rOoJ6Egrf8K2IrywzwOMk"));
        assert!(!is_episode_uri("spotify:track:4rOoJ6Egrf8K2IrywzwOMk"));
        assert!(!is_episode_uri("spotify:episode:"));
        assert!(!is_episode_uri(""));
    }
}
