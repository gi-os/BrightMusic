//! Podcast chapters, from Spotify's own display segments.
//!
//! Spotify has chapter marks for a lot of episodes and no public way to read them: the Web API's
//! `/chapters` endpoints are audiobooks (a chapter there *is* an episode), and the episode object
//! carries no segments at all. What its own clients draw comes from the extended-metadata service
//! on spclient, under `ExtensionKind::DISPLAY_SEGMENTS` — the same batched endpoint this app
//! already uses for track and album metadata, so there is nothing new to authenticate.
//!
//! Two kinds of thing arrive under that extension, and they are told apart by which `decoration`
//! the message carries, not by the segments themselves:
//!
//! - `podcast_chapters_decoration` — chapters. Either the publisher's (written into the show
//!   notes, or supplied in the feed) or Spotify's own, generated from a transcript.
//! - `music_and_talk_decoration` — a Music+Talk episode, where the segments are the licensed
//!   tracks played inside it. Those are not chapters and drawing them as chapters would put a
//!   mark on every song.
//!
//! Anything else is treated as "no chapters", which is also what an episode nobody has segmented
//! returns. Coverage is a minority of episodes and almost entirely English-language ones, so the
//! caller has to treat chapters as a bonus rather than a feature to build a screen around.
//!
//! The payload is decoded by hand. `librespot-protocol` compiles a fixed list of `.proto` files
//! and `display_segments_extension.proto` is not on it, so using the generated type would mean
//! patching a fourth librespot crate to add one message. The wire format is pinned by the tests at
//! the bottom of this file, against bytes a generated encoder produced for the same message.
//!
//! `spotify/displaysegments/v1/display_segments_extension.proto`, as of librespot 0.8.0:
//!
//! ```text
//! message DisplaySegmentsExtension {
//!   string episode_uri = 1;
//!   repeated DisplaySegment segments = 2;
//!   int32 duration_ms = 3;
//!   oneof decoration {
//!     MusicAndTalkDecoration music_and_talk_decoration = 4;
//!     PodcastChaptersDecoration podcast_chapters_decoration = 5;
//!   }
//! }
//! message DisplaySegment {
//!   string uri = 1;
//!   SegmentType type = 2;
//!   int32 duration_ms = 3;
//!   int32 seek_start_ms = 4;
//!   int32 seek_stop_ms = 5;
//!   optional string title = 6;
//!   optional string subtitle = 7;
//!   optional string image_url = 8;
//!   optional bool is_preview = 9;
//! }
//! ```

use std::sync::Arc;
use std::time::Duration;

use librespot::core::{Session, SpotifyUri};
use librespot::protocol::extended_metadata::{BatchedEntityRequest, EntityRequest, ExtensionQuery};
use librespot::protocol::extension_kind::ExtensionKind;
use protobuf::EnumOrUnknown;

use crate::SpotifyError;

/// Bound on the lookup. Chapters are decoration on a screen that is already playing audio; waiting
/// on them is never worth more than this.
const FETCH_TIMEOUT: Duration = Duration::from_secs(10);

/// One chapter of an episode, in the form the scrubber wants.
#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct EpisodeChapter {
    pub title: String,
    pub start_ms: i64,
    /// Exclusive. Falls back to the next chapter's start, or the episode's length, when Spotify
    /// gives no stop — a chapter with no end cannot be drawn as a band on the bar.
    pub end_ms: i64,
}

impl super::EngineShared {
    /// Spotify's chapters for an episode, or an empty list when it has none.
    ///
    /// Blocking: call it off the main thread. Empty is the ordinary answer, not a failure — most
    /// episodes have no chapters, and the caller falls back to whatever the show notes hold.
    pub fn episode_chapters(
        self: &Arc<Self>,
        uri: String,
    ) -> Result<Vec<EpisodeChapter>, SpotifyError> {
        let parsed = SpotifyUri::from_uri(&uri).map_err(|_| SpotifyError::InvalidUri {
            uri: uri.clone(),
        })?;
        // As in `resume.rs`: never build a session just to decorate a scrub bar.
        if !self.has_connected_active() {
            return Err(SpotifyError::NotLoggedIn);
        }
        let session = Self::session_or_err(self)?;
        let handle = self.runtime.handle().clone();
        handle.block_on(async move {
            match tokio::time::timeout(FETCH_TIMEOUT, fetch(&session, &parsed)).await {
                Ok(Ok(bytes)) => Ok(bytes
                    .as_deref()
                    .and_then(parse_display_segments)
                    .unwrap_or_default()),
                Ok(Err(e)) => Err(SpotifyError::Network { msg: e.to_string() }),
                Err(_) => Err(SpotifyError::Network {
                    msg: "chapter lookup timed out".to_string(),
                }),
            }
        })
    }
}

async fn fetch(
    session: &Session,
    uri: &SpotifyUri,
) -> Result<Option<Vec<u8>>, librespot::core::Error> {
    let req = BatchedEntityRequest {
        entity_request: vec![EntityRequest {
            entity_uri: uri.to_uri()?,
            query: vec![ExtensionQuery {
                extension_kind: EnumOrUnknown::new(ExtensionKind::DISPLAY_SEGMENTS),
                ..Default::default()
            }],
            ..Default::default()
        }],
        ..Default::default()
    };
    let res = session.spclient().get_extended_metadata(req).await?;
    for arr in res.extended_metadata {
        for mut entry in arr.extension_data {
            if let Some(any) = entry.extension_data.take() {
                if !any.value.is_empty() {
                    return Ok(Some(any.value));
                }
            }
        }
    }
    // No extension data is how "this episode has no segments" comes back. Not an error.
    Ok(None)
}

// --- protobuf decoding --------------------------------------------------------------------------

const WIRE_VARINT: u64 = 0;
const WIRE_64BIT: u64 = 1;
const WIRE_LEN: u64 = 2;
const WIRE_32BIT: u64 = 5;

struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }

    fn done(&self) -> bool {
        self.pos >= self.buf.len()
    }

    fn varint(&mut self) -> Option<u64> {
        let mut value = 0u64;
        let mut shift = 0u32;
        loop {
            let byte = *self.buf.get(self.pos)?;
            self.pos += 1;
            // Ten bytes is the most a u64 can occupy; more than that is a corrupt stream, and
            // shifting past 63 would panic in debug and silently wrap in release.
            if shift >= 64 {
                return None;
            }
            value |= u64::from(byte & 0x7f) << shift;
            if byte & 0x80 == 0 {
                return Some(value);
            }
            shift += 7;
        }
    }

    fn take(&mut self, len: usize) -> Option<&'a [u8]> {
        let end = self.pos.checked_add(len)?;
        let slice = self.buf.get(self.pos..end)?;
        self.pos = end;
        Some(slice)
    }

    /// Next (field number, wire type), or None at the end of the buffer or on a malformed tag.
    fn tag(&mut self) -> Option<(u64, u64)> {
        let key = self.varint()?;
        Some((key >> 3, key & 0x7))
    }

    fn len_delimited(&mut self) -> Option<&'a [u8]> {
        let len = self.varint()? as usize;
        self.take(len)
    }

    /// Step over a field this decoder does not care about. Unknown fields are normal — the message
    /// was extracted from a Windows client build and Spotify adds to it.
    fn skip(&mut self, wire: u64) -> Option<()> {
        match wire {
            WIRE_VARINT => self.varint().map(|_| ()),
            WIRE_64BIT => self.take(8).map(|_| ()),
            WIRE_LEN => self.len_delimited().map(|_| ()),
            WIRE_32BIT => self.take(4).map(|_| ()),
            // Groups. Deprecated, never sent here, and not worth a nesting stack to skip.
            _ => None,
        }
    }
}

#[derive(Default)]
struct Segment {
    start_ms: i64,
    stop_ms: i64,
    title: String,
}

/// Decode a `DisplaySegmentsExtension` into chapters, or `None` when it holds something else.
///
/// `None` rather than an empty list on purpose: "this is a Music+Talk episode" and "this episode
/// has two chapters, both untitled" are different answers, and only the first should let a caller
/// fall back to the show notes.
fn parse_display_segments(data: &[u8]) -> Option<Vec<EpisodeChapter>> {
    let mut reader = Reader::new(data);
    let mut segments: Vec<Segment> = Vec::new();
    let mut duration_ms = 0i64;
    let mut is_chapters = false;

    while !reader.done() {
        let (field, wire) = reader.tag()?;
        match (field, wire) {
            (2, WIRE_LEN) => {
                let bytes = reader.len_delimited()?;
                segments.push(parse_segment(bytes)?);
            }
            (3, WIRE_VARINT) => duration_ms = reader.varint()? as i64,
            (5, WIRE_LEN) => {
                // The decoration that says these segments are chapters. Its contents (tags) say
                // nothing the scrubber needs; its presence is the whole signal.
                reader.len_delimited()?;
                is_chapters = true;
            }
            _ => reader.skip(wire)?,
        }
    }

    if !is_chapters {
        return None;
    }
    Some(to_chapters(segments, duration_ms))
}

fn parse_segment(data: &[u8]) -> Option<Segment> {
    let mut reader = Reader::new(data);
    let mut segment = Segment::default();
    while !reader.done() {
        let (field, wire) = reader.tag()?;
        match (field, wire) {
            (4, WIRE_VARINT) => segment.start_ms = reader.varint()? as i64,
            (5, WIRE_VARINT) => segment.stop_ms = reader.varint()? as i64,
            (6, WIRE_LEN) => {
                let bytes = reader.len_delimited()?;
                // Lossy rather than a bail: a title with one bad byte in it is still a better mark
                // on the bar than no chapters at all.
                segment.title = String::from_utf8_lossy(bytes).into_owned();
            }
            _ => reader.skip(wire)?,
        }
    }
    Some(segment)
}

/// Sort, close the gaps, and drop what cannot be drawn.
fn to_chapters(mut segments: Vec<Segment>, duration_ms: i64) -> Vec<EpisodeChapter> {
    segments.retain(|s| s.start_ms >= 0);
    segments.sort_by_key(|s| s.start_ms);
    // Two segments starting at the same millisecond would draw two marks in one place; the second
    // is the one to keep, since it is the later revision of the same boundary.
    segments.dedup_by_key(|s| s.start_ms);

    let count = segments.len();
    let mut out = Vec::with_capacity(count);
    for (index, segment) in segments.iter().enumerate() {
        let next_start = segments.get(index + 1).map(|s| s.start_ms);
        let end = if segment.stop_ms > segment.start_ms {
            segment.stop_ms
        } else {
            next_start.unwrap_or(duration_ms)
        };
        out.push(EpisodeChapter {
            title: segment.title.clone(),
            start_ms: segment.start_ms,
            end_ms: end.max(segment.start_ms),
        });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A real `DisplaySegmentsExtension` with two chapters, serialized by a generated encoder.
    const CHAPTERS: &[u8] = &[
        0x0a, 0x13, 0x73, 0x70, 0x6f, 0x74, 0x69, 0x66, 0x79, 0x3a, 0x65, 0x70, 0x69, 0x73, 0x6f,
        0x64, 0x65, 0x3a, 0x61, 0x62, 0x63, 0x12, 0x26, 0x0a, 0x13, 0x73, 0x70, 0x6f, 0x74, 0x69,
        0x66, 0x79, 0x3a, 0x65, 0x70, 0x69, 0x73, 0x6f, 0x64, 0x65, 0x3a, 0x61, 0x62, 0x63, 0x10,
        0x01, 0x18, 0xe0, 0xb0, 0x0f, 0x28, 0xe0, 0xb0, 0x0f, 0x32, 0x05, 0x49, 0x6e, 0x74, 0x72,
        0x6f, 0x12, 0x2d, 0x10, 0x01, 0x18, 0xa0, 0xac, 0xcc, 0x01, 0x20, 0xe0, 0xb0, 0x0f, 0x28,
        0x80, 0xdd, 0xdb, 0x01, 0x32, 0x0d, 0x54, 0x68, 0x65, 0x20, 0x69, 0x6e, 0x74, 0x65, 0x72,
        0x76, 0x69, 0x65, 0x77, 0x3a, 0x0c, 0x77, 0x69, 0x74, 0x68, 0x20, 0x73, 0x6f, 0x6d, 0x65,
        0x6f, 0x6e, 0x65, 0x18, 0x80, 0xdd, 0xdb, 0x01, 0x2a, 0x06, 0x0a, 0x04, 0x61, 0x75, 0x74,
        0x6f,
    ];

    /// The same shape, decorated as Music+Talk: segments that are songs, not chapters.
    const MUSIC_AND_TALK: &[u8] = &[
        0x0a, 0x13, 0x73, 0x70, 0x6f, 0x74, 0x69, 0x66, 0x79, 0x3a, 0x65, 0x70, 0x69, 0x73, 0x6f,
        0x64, 0x65, 0x3a, 0x78, 0x79, 0x7a, 0x12, 0x10, 0x10, 0x02, 0x28, 0xe8, 0x07, 0x32, 0x09,
        0x53, 0x6f, 0x6d, 0x65, 0x20, 0x53, 0x6f, 0x6e, 0x67, 0x18, 0xe8, 0x07, 0x22, 0x02, 0x08,
        0x01,
    ];

    #[test]
    fn chapters_come_back_in_order_with_their_titles() {
        let chapters = parse_display_segments(CHAPTERS).expect("decorated as chapters");
        assert_eq!(
            chapters,
            vec![
                EpisodeChapter {
                    title: "Intro".to_string(),
                    start_ms: 0,
                    end_ms: 252_000,
                },
                EpisodeChapter {
                    title: "The interview".to_string(),
                    start_ms: 252_000,
                    end_ms: 3_600_000,
                },
            ]
        );
    }

    #[test]
    fn a_music_and_talk_episode_has_no_chapters() {
        // Its segments are the songs played in the episode. Drawing them as chapters would put a
        // mark on every track.
        assert_eq!(parse_display_segments(MUSIC_AND_TALK), None);
    }

    #[test]
    fn an_undecorated_message_has_no_chapters() {
        // Segments with neither decoration: the message says what they are with the oneof, and
        // without it there is nothing to say they are user-facing.
        let mut undecorated = CHAPTERS.to_vec();
        undecorated.truncate(undecorated.len() - 8); // drop the chapters decoration
        assert_eq!(parse_display_segments(&undecorated), None);
    }

    #[test]
    fn a_truncated_message_is_rejected_rather_than_half_read() {
        // Half a chapter list drawn on the bar is worse than none: the marks would be in the right
        // places and the last one would claim to run to the end of the episode.
        assert_eq!(parse_display_segments(&CHAPTERS[..40]), None);
    }

    #[test]
    fn unknown_fields_are_stepped_over() {
        // The message was extracted from a client build; Spotify adds fields to it. A new one must
        // not cost the chapters.
        let mut extended = CHAPTERS.to_vec();
        extended.extend_from_slice(&[0x78, 0x2a]); // field 15, varint 42
        extended.extend_from_slice(&[0x7a, 0x02, 0x68, 0x69]); // field 15, "hi"
        let chapters = parse_display_segments(&extended).expect("still chapters");
        assert_eq!(chapters.len(), 2);
    }

    #[test]
    fn a_missing_stop_falls_through_to_the_next_start() {
        let segments = vec![
            Segment { start_ms: 0, stop_ms: 0, title: "One".into() },
            Segment { start_ms: 60_000, stop_ms: 0, title: "Two".into() },
        ];
        let chapters = to_chapters(segments, 120_000);
        assert_eq!(chapters[0].end_ms, 60_000);
        assert_eq!(chapters[1].end_ms, 120_000);
    }

    #[test]
    fn segments_out_of_order_are_sorted_and_deduped() {
        let segments = vec![
            Segment { start_ms: 60_000, stop_ms: 90_000, title: "Two".into() },
            Segment { start_ms: 0, stop_ms: 60_000, title: "One".into() },
            Segment { start_ms: 60_000, stop_ms: 90_000, title: "Two again".into() },
        ];
        let chapters = to_chapters(segments, 90_000);
        assert_eq!(chapters.len(), 2);
        assert_eq!(chapters[0].title, "One");
        assert_eq!(chapters[1].title, "Two again");
    }
}
