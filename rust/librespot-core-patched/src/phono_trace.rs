//! Phono: the engine's own account of a load, for the stall report.
//!
//! When a spinner has been up for ten seconds with no audio, the app files a report. That report
//! could say what the app believed (online, session connected, the play call returned) but nothing
//! about where the engine stopped: the player thread, the track loader, storage-resolve and the CDN
//! all sit below the FFI line and left nothing a phone could send.
//!
//! This is that record: a small process-wide ring of short, timestamped lines. Every layer appends
//! to the same ring (the player thread, the loader thread, the CDN fetch, the engine's session code)
//! so one read gives the order things actually happened in. The app reads it through
//! `LibrespotEngine::load_trace` when it files the report and merges it with its own steps.
//!
//! Rules for anything written here:
//! - **Short and cheap.** One `format!` and a push under a lock that is held for a moment. Never
//!   from per-packet code: position ticks and decoded packets stay out, or one track of audio would
//!   push the load it is meant to explain out of the ring.
//! - **No secrets.** A CDN URL carries its access token in the query string, so a URL is only ever
//!   written down as its host. See [url_host].
//! - **Never fails.** A poisoned lock is used anyway. Tracing must not be the thing that breaks
//!   playback.

use std::collections::VecDeque;
use std::fmt::Display;
use std::sync::{Mutex, MutexGuard};
use std::time::Instant;

/// One load's worth of steps plus the context around it. Old lines fall off the front.
const CAPACITY: usize = 200;

/// Longest line kept. An error message can be any length; the ring should not grow with it.
const MAX_LINE: usize = 240;

static RING: Mutex<VecDeque<(Instant, String)>> = Mutex::new(VecDeque::new());

fn ring() -> MutexGuard<'static, VecDeque<(Instant, String)>> {
    RING.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Append one step.
pub fn note(line: impl Into<String>) {
    let mut line: String = line.into();
    if line.len() > MAX_LINE {
        let mut cut = MAX_LINE;
        while cut > 0 && !line.is_char_boundary(cut) {
            cut -= 1;
        }
        line.truncate(cut);
        line.push_str("...");
    }
    let at = Instant::now();
    let mut ring = ring();
    if ring.len() >= CAPACITY {
        ring.pop_front();
    }
    ring.push_back((at, line));
}

/// Every step still held, oldest first, as (milliseconds before now, line).
///
/// Ages rather than timestamps, so the caller can place each line on its own clock without the two
/// sides having to share one.
pub fn snapshot() -> Vec<(u64, String)> {
    let now = Instant::now();
    ring()
        .iter()
        .map(|(at, line)| {
            let age = now.saturating_duration_since(*at).as_millis();
            (u64::try_from(age).unwrap_or(u64::MAX), line.clone())
        })
        .collect()
}

/// A short tag for a file or track id, enough to tell two loads apart in one report.
pub fn tag(id: impl Display) -> String {
    id.to_string().chars().take(6).collect()
}

/// The host of a URL and nothing else.
///
/// Scheme, credentials, port, path and query are all dropped. The query is the part that matters:
/// Spotify's CDN URLs carry a signed access token there, and the report is posted to a tracker.
pub fn url_host(url: &str) -> &str {
    let rest = match url.find("://") {
        Some(i) => &url[i + 3..],
        None => url,
    };
    let end = rest
        .find(|c: char| c == '/' || c == '?' || c == '#')
        .unwrap_or(rest.len());
    let authority = &rest[..end];
    let host_port = match authority.rfind('@') {
        Some(i) => &authority[i + 1..],
        None => authority,
    };
    if host_port.starts_with('[') {
        return match host_port.find(']') {
            Some(i) => &host_port[..=i],
            None => host_port,
        };
    }
    match host_port.find(':') {
        Some(i) => &host_port[..i],
        None => host_port,
    }
}
