package space.coljac.FreeAudio.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle

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
        
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .build()
        
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startForeground(NOTIFICATION_ID, buildNotification())
                } else {
                    stopForeground(false)
                    notificationManager.notify(NOTIFICATION_ID, buildNotification())
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                updateNotification()
            }
        })

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

    private fun buildNotification(): Notification {
        val mediaSession = mediaSession ?: return Notification()
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(player.mediaMetadata.title ?: "Unknown Title")
            .setContentText(player.mediaMetadata.artist ?: "Unknown Artist")
            .setContentIntent(mediaSession.sessionActivity)
            .setStyle(NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)

        // Add playback controls
        val playPauseIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AudioService::class.java).apply {
                action = if (player.isPlaying) "ACTION_PAUSE" else "ACTION_PLAY"
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        builder.addAction(
            if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            if (player.isPlaying) "Pause" else "Play",
            playPauseIntent
        )

        return builder.build()
    }

    private fun updateNotification() {
        if (player.isPlaying) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        }
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