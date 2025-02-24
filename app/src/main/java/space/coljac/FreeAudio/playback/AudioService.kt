package space.coljac.FreeAudio.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import space.coljac.FreeAudio.MainActivity
import space.coljac.FreeAudio.R

private const val NOTIFICATION_ID = 123
private const val CHANNEL_ID = "FreeAudio_playback_channel"

@UnstableApi
class AudioService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()

        // Initialize ExoPlayer
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .build()

        // Create MediaSession
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        // Update notification on player state changes
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updateNotificationState()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateNotificationState()
            }
        })
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Audio playback controls"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun updateNotificationState() {
        val notification = createForegroundNotification(mediaSession!!)
        if (player.isPlaying) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            stopForeground(false)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createForegroundNotification(mediaSession: MediaSession): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FreeAudio")
            .setContentText(if (player.isPlaying) "Playing" else "Paused")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(player.isPlaying)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_PLAY" -> player.play()
            "ACTION_PAUSE" -> player.pause()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
