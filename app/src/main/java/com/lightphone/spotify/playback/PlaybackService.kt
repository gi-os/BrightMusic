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
 * LightOS's lock screen is not the Android keyguard — it is the `com.lightos` system app's own
 * activity, force-started when the screen goes off. So it renders media controls by reading the
 * platform media session, and everything below exists to make sure there is one to read.
 *
 * Three things had to be true, and none of them were:
 *
 *  1. **The session has to be registered with the service.** Building one is not enough; see
 *     [ensureEngineAndSession]. This was the actual bug — audio played for months with a session that
 *     nothing outside the process could see.
 *  2. **The notification channel has to be visible to LightOS.** See [ensureNotificationChannel].
 *  3. **Rewind and forward have to be asked for by name.** See [applyMediaButtonPreferences].
 *
 * All three now hold, confirmed on a real LPIII: `dumpsys media_session` lists this package
 * `active=true state=PLAYING` as the media button session, and the notification is a MediaStyle one at
 * importance 3 with `vis=PUBLIC` and three actions. Whether LightOS's own lock screen chooses to draw
 * any of that is out of this app's hands — the SDK's emulator renders its lock screen as a clock and a
 * battery icon with no media row at all.
 *
 * The plain notification this service posts itself is only a placeholder, for the window between
 * `startForegroundService` and the session being ready — the native engine can take longer to attach
 * than the five seconds Android allows. It is posted **only while there is no session**: every
 * transport action calls `ensureServiceStarted`, so an unguarded placeholder reappeared next to the
 * real notification, which is what the device dump showed.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    @Volatile
    private var foregroundStarted = false

    /** Whether the "Starting playback…" placeholder is still on screen. */
    private var placeholderPosted = false

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
        // Pin the channel, not the id. By default the provider posts on its own `default_channel_id`
        // at IMPORTANCE_LOW, which on LightOS is invisible — see ensureNotificationChannel.
        //
        // The id is deliberately left as Media3's own. Sharing ours would mean Media3 cancelling this
        // service's placeholder whenever it decides not to show a notification, which drops the
        // foreground state without telling us. The placeholder is dismissed explicitly instead, once
        // Media3 has posted.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .build(),
        )
        promoteToForeground(getString(R.string.playback_notification_initializing))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Unconditional, because the deadline is unconditional: every startForegroundService has to
        // be answered by a startForeground within five seconds or the process is killed, and whether
        // a session happens to exist is not part of that bargain. This used to be guarded on
        // `mediaSession == null` as well, to keep a "Starting playback…" placeholder from appearing
        // beside Media3's real notification. But once Media3 pauses it calls stopForeground, which
        // clears `foregroundStarted` while the session stays built — so from the first pause onward
        // *neither* promotion path could ever run again, and the next warm-on-open took the app down
        // with a ForegroundServiceDidNotStartInTime.
        if (!foregroundStarted) {
            promoteToForeground(getString(R.string.playback_notification_initializing))
            // The duplicate notification the old guard was protecting against, dealt with the other
            // way round: answer the deadline, then stand down immediately. With a session already
            // built and nothing playing, this start was somebody warming the engine rather than
            // asking for audio, so there is nothing for a foreground service to be in the foreground
            // for and nothing worth saying in the shade. Media3 promotes it again on the next play.
            if (mediaSession != null) {
                val s = PlaybackController.get(this).state.value
                if (!s.isPlaying && !s.isLoading) releaseForeground()
            }
        }
        ensureEngineAndSession()
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * Drop the foreground state and the placeholder together.
     *
     * Safe straight after `startForeground`: the five-second rule is about whether the service ever
     * posted, not about how long it stayed up.
     */
    private fun releaseForeground() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        foregroundStarted = false
        placeholderPosted = false
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
        // Same correction as in onStartCommand: the session exists by definition here — it is the
        // argument — so the old `mediaSession == null` made this dead code. Media3 posts its own
        // notification under its own id immediately after, and dismissPlaceholder below takes this
        // one away again, so the bridge lasts a moment and is never what the user looks at.
        if (shouldForeground && !foregroundStarted) {
            promoteToForeground()
        }
        super.onUpdateNotification(session, shouldForeground)
        // Media3 owns the notification from here, under its own id. The placeholder would otherwise sit
        // next to it saying "Starting playback…" for as long as the service lived.
        dismissPlaceholder()
        if (!shouldForeground) {
            // Media3 has just called stopForeground, so the service is no longer in the foreground and
            // the next startForegroundService has to post something again. Leaving this flag set meant
            // nothing did, and the five-second deadline passed with a
            // ForegroundServiceDidNotStartInTime.
            foregroundStarted = false
        }
    }

    /**
     * Drop the placeholder once the real media notification exists.
     *
     * Safe to call after `super.onUpdateNotification`: by then Media3 has either called startForeground
     * with its own id — which is what the service's foreground state is bound to — or decided not to
     * show anything, in which case there is nothing worth keeping either.
     */
    private fun dismissPlaceholder() {
        if (!placeholderPosted) return
        placeholderPosted = false
        runCatching {
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
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
        val session = MediaSession.Builder(this, LibrespotPlayer(controller))
            // So tapping the lock-screen controls opens the player rather than doing nothing.
            .setSessionActivity(openAppIntent())
            .build()
        mediaSession = session
        // THE thing that was missing, and why nothing ever appeared on the lock screen.
        //
        // Building a session does not register it with the service. `addSession` is the only writer of
        // the service's session map, and the only caller of MediaNotificationManager.addSession — which
        // is what connects an internal MediaController to the session and starts posting the media
        // notification. Without it, `updateNotification` is never reached, and short-circuits on
        // `!isSessionAdded(session)` even if it were.
        //
        // Media3 does call addSession for you, but only from the paths that invoke `onGetSession`: a
        // controller binding the service, a legacy MediaBrowser binding it, or a **media-button**
        // intent in onStartCommand. This app has none of those — the UI drives PlaybackController
        // directly rather than through a MediaController, nothing external binds, and the service is
        // started with a plain startForegroundService intent, which `isMediaAction` rejects. So the
        // session sat there, playing audio, invisible to everything outside the process.
        //
        // Idempotent for the same instance, so the `onGetSession` path re-adding it is harmless.
        addSession(session)
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
            placeholderPosted = true
        } else {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
            placeholderPosted = true
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
