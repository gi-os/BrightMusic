#!/usr/bin/env bash
#
# Cross-compiles rust/spotify-core for Android and generates the UniFFI Kotlin
# bindings. Adapted for the Kyocera KY-42C (armeabi-v7a, 32-bit ARM).
#
# Prerequisites:
#   - Rust toolchain (rustup) with the Android target installed:
#       rustup target add armv7-linux-androideabi
#   - cargo-ndk:   cargo install cargo-ndk
#   - Android NDK installed and ANDROID_NDK_HOME (or ANDROID_NDK_ROOT) set.
#
# Output:
#   - app/src/main/jniLibs/armeabi-v7a/libspotify_core.so
#   - app/src/main/java/com/lightphone/spotify/ffi/<generated>.kt

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CRATE_DIR="$REPO_ROOT/rust/spotify-core"
JNILIBS_DIR="$REPO_ROOT/app/src/main/jniLibs"
BINDINGS_DIR="$REPO_ROOT/app/src/main/java"

# Kyocera KY-42C is armeabi-v7a (32-bit ARM).
read -r -a ABIS <<< "${ANDROID_ABIS:-armv7-linux-androideabi}"

echo "==> Cross-compiling spotify-core (release) for: ${ABIS[*]}"
NDK_ARGS=()
for abi in "${ABIS[@]}"; do
    NDK_ARGS+=(-t "$abi")
done

(
    cd "$CRATE_DIR"
    # audiotrack-sink: pure JNI AudioTrack, no rodio/cpal, no libc++_shared needed.
    CARGO_FEATURES=(--no-default-features --features audiotrack-sink)
    cargo ndk "${NDK_ARGS[@]}" -o "$JNILIBS_DIR" \
        --platform 29 \
        build --release "${CARGO_FEATURES[@]}"
)

echo "==> Generating UniFFI Kotlin bindings"
(
    cd "$CRATE_DIR"
    CARGO_PROFILE_RELEASE_STRIP=false cargo build --release
    TARGET_DIR="${CARGO_TARGET_DIR:-$CRATE_DIR/target}"
    HOST_LIB=""
    for ext in dylib so; do
        cand="$TARGET_DIR/release/libspotify_core.$ext"
        [ -f "$cand" ] && HOST_LIB="$cand" && break
    done
    [ -z "$HOST_LIB" ] && { echo "ERROR: host libspotify_core not found in $TARGET_DIR/release"; exit 1; }
    cargo run --release --bin uniffi-bindgen -- generate \
        --library "$HOST_LIB" \
        --language kotlin \
        --out-dir "$BINDINGS_DIR"
)

echo "==> Done."
