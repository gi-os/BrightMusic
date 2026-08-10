package com.lightphone.spotify

import android.content.Context

/**
 * Bootstraps the native library.
 *
 * [initAndroidContext] is a raw-JNI entrypoint (implemented in Rust in
 * `android_ctx.rs`). It must run once, before any playback, so cpal's AAudio
 * backend can read the JavaVM/Context from `ndk_context` when rodio is used.
 *
 * [registerAudioSink] caches JNI method IDs for [PhonoAudioTrackSink] when the
 * audiotrack backend is compiled in (Path C).
 */
object NativeInit {
    @Volatile
    private var initialized = false

    external fun installSeccompFix()

    fun ensureLoaded(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            System.loadLibrary("spotify_core")
            // Install SIGSYS handler before any file I/O — the Kyocera's
            // kernel 4.9 has no statx syscall and seccomp kills the process.
            installSeccompFix()
            initAndroidContext(context.applicationContext)
            // Kyocera always uses the AudioTrack sink (Path C).
            registerAudioSink()
            initialized = true
        }
    }

    private external fun initAndroidContext(context: Context)
    private external fun registerAudioSink()
}
