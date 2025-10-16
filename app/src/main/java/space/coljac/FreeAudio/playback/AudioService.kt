package space.coljac.FreeAudio.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import space.coljac.FreeAudio.MainActivity
import space.coljac.FreeAudio.R

private const val TAG = "AudioService"
private const val NOTIFICATION_CHANNEL_ID = "fba_playback_channel"
private const val NOTIFICATION_CHANNEL_NAME = "Audio Playback"

@UnstableApi
class AudioService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AudioService onCreate")

        // Create notification channel for Android O and above
        createNotificationChannel()

        // Initialize ExoPlayer with wake lock to prevent sleep during playback
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK) // Keep CPU awake during playback
            .build()

        // Create MediaSession
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setId("FreeAudio_media_session")
            .build()

        // Optional: Add a listener for logging
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "Service onIsPlayingChanged=$isPlaying index=${player.currentMediaItemIndex}")
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                Log.d(TAG, "Service onMediaItemTransition reason=$reason index=${player.currentMediaItemIndex} title=${mediaItem?.mediaMetadata?.title}")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(TAG, "Service onPlaybackStateChanged state=$playbackState index=${player.currentMediaItemIndex}")
            }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // Low importance to avoid intrusive notifications
            ).apply {
                description = "Controls for audio playback"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        Log.d(TAG, "AudioService onDestroy")
        mediaSession?.run {
            release()
            mediaSession = null
        }
        player.release()
        super.onDestroy()
    }
}
