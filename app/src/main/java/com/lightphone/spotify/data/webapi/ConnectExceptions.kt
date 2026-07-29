package com.lightphone.spotify.data.webapi

/**
 * Typed failures for the Spotify Connect endpoints. Each one needs a different UI
 * response, which is why they are not all plain `IOException`s.
 */
sealed class ConnectException(message: String) : Exception(message)

/**
 * Spotify returned 404 NO_ACTIVE_DEVICE: the target went away between listing and
 * acting on it. Speakers idle out constantly, so this is an expected outcome — refresh
 * the device list rather than surfacing it as a failure.
 */
class ConnectNoActiveDeviceException :
    ConnectException("That device is no longer available")

/**
 * 403 on a device that will not accept remote control. Web-player tabs and some
 * third-party speakers report `is_restricted`, and casting to them cannot be made to
 * work from here.
 */
class ConnectRestrictedException(detail: String) :
    ConnectException("That device does not allow remote control ($detail)")

/**
 * 403 for insufficient scope. Means the stored refresh token predates the
 * `user-read-playback-state` / `user-modify-playback-state` scopes this fork added, so
 * the user has to re-run the Step 2 authorize to mint a token that includes them.
 */
class ConnectScopeException :
    ConnectException("Re-authorize Step 2 to allow device control")
