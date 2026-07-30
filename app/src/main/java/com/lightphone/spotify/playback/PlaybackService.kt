package com.lightphone.spotify.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lightphone.spotify.MainActivity
import com.lightphone.spotify.data.isEpisodeUri
import com.lightphone.spotify.R

/**
 * Foreground service that hosts the Media3 [MediaSession] backed by
 * [LibrespotPlayer]. Engine creation is deferred until first playback or login.
 *
 * ### The lock screen
 * LightOS draws its media controls from the platform media session, which Media3 publishes from its
 * own MediaStyle notification. This service used to post a second, plain notification of its own to
 * satisfy the five-second `startForeground` deadline, under a different id — so two notifications
 * existed and the one that owned the foreground state carried no session and no buttons.
 *
 * The provider is now pinned to this service's own notification id and channel, so Media3's media
 * notification *is* the foreground notification. The plain one is only a placeholder for the window
 * before the session exists, and is replaced rather than joined.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    @Volatile
    private var foregroundStarted = false

    /**
     * Whether the podcast buttons are currently applied.
     *
     * Null until the first state arrives, so the first call always applies — otherwise starting the app
     * straight into an episode would keep the music layout.
     */
    private var episodeButtonsApplied: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        // The channel is the load-bearing half: by default the provider posts on its own
        // `default_channel_id` at IMPORTANCE_LOW, which on LightOS is invisible (see
        // ensureNotificationChannel). Pinning our id too means Media3's media notification replaces
        // the placeholder below rather than sitting next to it, so there is one notification.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .build(),
        )
        promoteToForeground(getString(R.string.playback_notification_initializing))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            promoteToForeground(getString(R.string.playback_notification_initializing))
        }
        ensureEngineAndSession()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        ensureEngineAndSession()
        return mediaSession
    }

    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        val s = PlaybackController.get(this).state.value
        applyMediaButtonPreferences(session, s)
        val shouldForeground = startInForegroundRequired || s.isPlaying || s.isLoading
        if (shouldForeground && !foregroundStarted) {
            promoteToForeground()
        }
        super.onUpdateNotification(session, shouldForeground)
        if (!shouldForeground) {
            // Media3 has just called stopForeground, and with the notification id now shared it also
            // cancels the placeholder. So the service is no longer in the foreground and the next
            // startForegroundService has to post again — leaving this flag set meant nothing did, and
            // the five-second deadline passed with a ForegroundServiceDidNotStartInTime.
            foregroundStarted = false
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val s = PlaybackController.get(this).state.value
        if (s.isPlaying || s.isLoading) {
            return
        }
        stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        PlaybackEngineHolder.clearService()
        foregroundStarted = false
        super.onDestroy()
    }

    private fun ensureEngineAndSession() {
        if (mediaSession != null) return
        val controller = PlaybackController.get(this)
        PlaybackEngineHolder.ensureEngineAttached(this, controller)
        if (mediaSession != null) return
        mediaSession = MediaSession.Builder(this, LibrespotPlayer(controller))
            // So tapping the lock-screen controls opens the player rather than doing nothing.
            .setSessionActivity(openAppIntent())
            .build()
        PlaybackEngineHolder.markServiceReady()
    }

    /**
     * Swap the outer two lock-screen buttons for 15-second jumps on a podcast.
     *
     * `DefaultMediaNotificationProvider` only ever draws three buttons, from the player's commands:
     * previous, play/pause, next. `COMMAND_SEEK_BACK` and `COMMAND_SEEK_FORWARD` are never given
     * buttons of their own, so a rewind control has to be requested explicitly — which is what media
     * button preferences are for. Asking for the back and forward *slots* means the system puts them
     * where the skip buttons were rather than appending a fourth and fifth control.
     *
     * The glyphs are Media3's own `ICON_SKIP_BACK_15` / `ICON_SKIP_FORWARD_15`, so the lock screen says
     * 15 and means it.
     *
     * Music is left with previous/next, which is what those buttons are for there — an album has a next
     * track, an episode does not.
     */
    private fun applyMediaButtonPreferences(session: MediaSession, state: PlaybackUiState) {
        val isEpisode = state.currentUri.isEpisodeUri()
        // Only on a transition. Setting preferences rebuilds the notification, which calls back into
        // onUpdateNotification — doing it unconditionally would be a loop.
        if (isEpisode == episodeButtonsApplied) return
        episodeButtonsApplied = isEpisode
        session.setMediaButtonPreferences(
            if (!isEpisode) {
                emptyList()
            } else {
                listOf(
                    CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
                        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                        .setSlots(CommandButton.SLOT_BACK)
                        .setDisplayName(getString(R.string.playback_action_back_fifteen))
                        .build(),
                    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_15)
                        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                        .setSlots(CommandButton.SLOT_FORWARD)
                        .setDisplayName(getString(R.string.playback_action_forward_fifteen))
                        .build(),
                )
            },
        )
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * The playback channel, at `IMPORTANCE_DEFAULT`.
     *
     * This was `IMPORTANCE_LOW`, and on LightOS that is the difference between showing and not showing.
     * A `dumpsys notification` on a real LPIII put every user-facing notification at importance 3 or 4
     * and everything the shell ignores at 2 — and this app's playback notification was sitting at 2,
     * which is why the lock screen had nothing on it while other players did.
     *
     * Sound and vibration are switched off explicitly rather than relying on the importance: level 3
     * would otherwise be allowed to make a noise, and a media notification that pings when a track
     * changes is worse than one that is quiet and invisible.
     *
     * The channel id is versioned because a channel's importance is fixed at creation — Android
     * silently ignores changes to an existing one, so raising it for anyone who already has the app
     * installed requires a new id. The old channel is deleted so it does not linger in settings.
     */
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.playback_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.playback_notification_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(channel)
        runCatching { manager.deleteNotificationChannel(LEGACY_NOTIFICATION_CHANNEL_ID) }
    }

    /**
     * Placeholder foreground notification, for the window before the session exists.
     *
     * Android gives a foreground service five seconds to post something, which can be sooner than the
     * native engine and session are ready. Media3 replaces this at the same id once it has a session to
     * attach, so it is never what the user ends up looking at.
     */
    private fun promoteToForeground(
        contentText: String = getString(R.string.playback_notification_fallback),
    ) {
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .build()
        if (!foregroundStarted) {
            Log.i(TAG, "startForeground (first promotion)")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        } else {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "PlaybackService"
        /** Versioned: see [ensureNotificationChannel] for why the id had to change. */
        const val NOTIFICATION_CHANNEL_ID = "phono_playback_media"

        private const val LEGACY_NOTIFICATION_CHANNEL_ID = "phono_playback"
        private const val NOTIFICATION_ID = 0x70686f6e // "phon"
    }
}
