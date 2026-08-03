//! Durable offline pins: decrypt Spotify CDN audio to clear Ogg under
//! `{filesDir}/spotify-downloads/`, separate from the streaming LRU cache.

use std::fs::{self, File};
use std::io::{self, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use librespot::audio::{AudioDecrypt, AudioFile, Range};
use librespot::core::{Session, SpotifyId, SpotifyUri};
use librespot::metadata::audio::AudioFiles;

use crate::settings::StreamingQuality;
use crate::{parse_uri, resolve_playable_file, SpotifyError};

const SPOTIFY_OGG_HEADER_END: u64 = 0xa7;
const FETCH_CHUNK: usize = 256 * 1024;
const DOWNLOAD_DEADLINE: Duration = Duration::from_secs(10 * 60);

/// The id and base62 name of anything that can be pinned.
///
/// Tracks and podcast episodes are pinned identically — an episode is just another audio item behind
/// an audio key, and `AudioItem::get_file` resolves both. Only the uri check distinguished them, and
/// matching `SpotifyUri::Track` alone was enough to fail **every** podcast download with `InvalidUri`
/// before a single byte moved. Anything else (album, artist, playlist, local file) genuinely has no
/// audio of its own and is still rejected.
fn pinnable(uri: &str) -> Result<(SpotifyUri, SpotifyId, String), SpotifyError> {
    let spotify_uri = parse_uri(uri)?;
    let id = match &spotify_uri {
        SpotifyUri::Track { id } | SpotifyUri::Episode { id } => *id,
        _ => {
            return Err(SpotifyError::InvalidUri {
                uri: uri.to_string(),
            })
        }
    };
    let base62 = id.to_base62().map_err(|_| SpotifyError::InvalidUri {
        uri: uri.to_string(),
    })?;
    Ok((spotify_uri, id, base62))
}

/// Absolute pin directory: sibling of `spotify-cache` → `…/spotify-downloads`.
pub fn downloads_dir(cache_base: &Path) -> PathBuf {
    cache_base
        .parent()
        .unwrap_or(cache_base)
        .join("spotify-downloads")
}

pub fn quality_label(quality: StreamingQuality) -> &'static str {
    match quality {
        StreamingQuality::Low => "LOW",
        StreamingQuality::Normal => "NORMAL",
        StreamingQuality::High => "HIGH",
    }
}

pub fn pin_path(downloads: &Path, track_id_base62: &str, quality: StreamingQuality) -> PathBuf {
    downloads.join(format!(
        "{}_{}.ogg",
        track_id_base62,
        quality_label(quality)
    ))
}

/// Any completed pin for this track (any quality), newest-preferred by path sort.
pub fn find_any_pin(downloads: &Path, track_id_base62: &str) -> Option<PathBuf> {
    if !downloads.is_dir() {
        return None;
    }
    let prefix = format!("{track_id_base62}_");
    let mut matches: Vec<PathBuf> = fs::read_dir(downloads)
        .ok()?
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| {
            p.extension().and_then(|e| e.to_str()) == Some("ogg")
                && p.file_stem()
                    .and_then(|s| s.to_str())
                    .map(|s| s.starts_with(&prefix) || s == track_id_base62)
                    .unwrap_or(false)
        })
        .collect();
    matches.sort();
    matches.pop()
}

pub fn is_downloaded(downloads: &Path, uri: &str) -> bool {
    let Ok((_, _, base62)) = pinnable(uri) else {
        return false;
    };
    find_any_pin(downloads, &base62).is_some()
}

pub fn remove_download(downloads: &Path, uri: &str) -> Result<(), SpotifyError> {
    let (_, _, base62) = pinnable(uri)?;
    let prefix = format!("{base62}_");
    if downloads.is_dir() {
        for entry in fs::read_dir(downloads).map_err(|e| SpotifyError::Internal {
            msg: e.to_string(),
        })? {
            let entry = entry.map_err(|e| SpotifyError::Internal {
                msg: e.to_string(),
            })?;
            let path = entry.path();
            let name = path
                .file_stem()
                .and_then(|s| s.to_str())
                .unwrap_or_default();
            if name.starts_with(&prefix) || name == base62 {
                let _ = fs::remove_file(&path);
            }
        }
    }
    Ok(())
}

/// A place to send byte counts as a pin is fetched.
///
/// Boxed rather than a generic parameter because the only implementor that matters crosses the FFI
/// boundary as a `Box<dyn DownloadProgressListener>`, and `download_track` is called from
/// `spawn_blocking`, so the sink has to be `Send` and owned rather than borrowed.
pub type ProgressSink = Box<dyn Fn(u64, u64) + Send + Sync>;

pub async fn download_track(
    session: &Session,
    downloads: &Path,
    uri: &str,
    quality: StreamingQuality,
    progress: Option<ProgressSink>,
) -> Result<DownloadInfo, SpotifyError> {
    fs::create_dir_all(downloads).map_err(|e| SpotifyError::Internal {
        msg: format!("mkdir downloads: {e}"),
    })?;

    let (spotify_uri, track_id, base62) = pinnable(uri)?;

    let bitrate = quality.to_bitrate();
    let (file_id, bps, format) = resolve_playable_file(session, spotify_uri, bitrate)
        .await
        .ok_or_else(|| SpotifyError::Internal {
            msg: format!("no playable file for {uri}"),
        })?;

    let encrypted = AudioFile::open(session, file_id, bps)
        .await
        .map_err(|e| SpotifyError::Internal {
            msg: format!("AudioFile::open: {e}"),
        })?;

    let key = session
        .audio_key()
        .request(track_id, file_id)
        .await
        .map_err(|e| SpotifyError::Internal {
            msg: format!("audio key: {e}"),
        })?;

    let dest = pin_path(downloads, &base62, quality);
    let quality_str = quality_label(quality).to_string();
    let uri_owned = uri.to_string();

    tokio::task::spawn_blocking(move || {
        let slc = encrypted
            .get_stream_loader_controller()
            .map_err(|e| SpotifyError::Internal {
                msg: format!("stream loader: {e}"),
            })?;
        slc.set_random_access_mode();
        let len = slc.len();
        if len == 0 {
            return Err(SpotifyError::Internal {
                msg: "empty audio file".into(),
            });
        }
        fetch_entire_file(&slc, len, progress.as_deref())?;

        let mut decrypted = AudioDecrypt::new(Some(key), encrypted);
        let skip = if AudioFiles::is_ogg_vorbis(format) {
            SPOTIFY_OGG_HEADER_END
        } else {
            0
        };
        if skip > 0 {
            decrypted
                .seek(SeekFrom::Start(skip))
                .map_err(|e| SpotifyError::Internal {
                    msg: format!("seek ogg header: {e}"),
                })?;
        }

        let tmp = dest.with_extension("ogg.partial");
        {
            let mut out = File::create(&tmp).map_err(|e| SpotifyError::Internal {
                msg: format!("create pin: {e}"),
            })?;
            io::copy(&mut decrypted, &mut out).map_err(|e| SpotifyError::Internal {
                msg: format!("write pin: {e}"),
            })?;
            out.flush().map_err(|e| SpotifyError::Internal {
                msg: e.to_string(),
            })?;
        }
        fs::rename(&tmp, &dest).map_err(|e| SpotifyError::Internal {
            msg: format!("rename pin: {e}"),
        })?;

        let bytes = fs::metadata(&dest).map(|m| m.len()).unwrap_or(0);
        Ok(DownloadInfo {
            uri: uri_owned,
            path: dest.to_string_lossy().into_owned(),
            quality: quality_str,
            bytes,
        })
    })
    .await
    .map_err(|e| SpotifyError::Internal {
        msg: format!("download join: {e}"),
    })?
}

/// Pull the whole encrypted file down, reporting how far along it is.
///
/// This loop is where a download actually spends its time — the decrypt-and-write pass afterwards is
/// local work on bytes that have already arrived, and finishes in well under a second. So the fetch
/// offset is the only honest progress signal there is, and the numbers reported are **encrypted CDN
/// bytes**, which differ from the size of the finished pin by the Ogg header this strips. Close
/// enough for a bar; the completed row still records the real file size.
fn fetch_entire_file(
    slc: &librespot::audio::StreamLoaderController,
    len: usize,
    progress: Option<&(dyn Fn(u64, u64) + Send + Sync)>,
) -> Result<(), SpotifyError> {
    let deadline = Instant::now() + DOWNLOAD_DEADLINE;
    let mut offset = 0usize;
    if let Some(report) = progress {
        report(0, len as u64);
    }
    while offset < len {
        if Instant::now() > deadline {
            return Err(SpotifyError::Internal {
                msg: "download timed out".into(),
            });
        }
        let chunk = (len - offset).min(FETCH_CHUNK);
        slc.fetch_blocking(Range::new(offset, chunk))
            .map_err(|e| SpotifyError::Internal {
                msg: format!("fetch chunk @{offset}: {e}"),
            })?;
        offset += chunk;
        if let Some(report) = progress {
            report(offset as u64, len as u64);
        }
    }
    Ok(())
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct DownloadInfo {
    pub uri: String,
    pub path: String,
    pub quality: String,
    pub bytes: u64,
}
