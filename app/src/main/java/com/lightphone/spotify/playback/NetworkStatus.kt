package com.lightphone.spotify.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the phone actually has internet, not merely a network attached.
 *
 * `NET_CAPABILITY_INTERNET` is a claim the transport makes about itself: it is set on a
 * captive-portal Wi-Fi and on a cellular link that is registered but carrying no data.
 * `NET_CAPABILITY_VALIDATED` is the platform's finding that traffic actually reached the internet,
 * which is the question being asked.
 *
 * This lives outside [PlaybackController] because the engine can be built without one — the
 * download service does exactly that — and the answer has to be the same wherever it is asked.
 */
object NetworkStatus {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
