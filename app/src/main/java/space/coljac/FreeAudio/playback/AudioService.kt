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
import java.io.File
import space.coljac.FreeAudio.data.Talk
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes as AndroidAudioAttributes
import android.os.Build
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import android.os.Bundle
import androidx.core.app.NotificationCompat.Action
import androidx.core.graphics.drawable.IconCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import android.support.v4.media.session.MediaSessionCompat

private const val NOTIFICATION_ID = 123
private const val CHANNEL_ID = "FreeAudio_playback_channel"

@UnstableApi
class AudioService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var playWhenAudioFocusGained = false
    private lateinit var mediaSessionCompat: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
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

        // Create MediaSession with callback
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val connectionResult = super.onConnect(session, controller)
                    // Configure additional commands you want to support
                    val availableCommands = connectionResult.availableSessionCommands
                        .buildUpon()
                        .add(androidx.media3.session.SessionCommand("ACTION_DOWNLOAD", Bundle()))
                        .add(androidx.media3.session.SessionCommand("ACTION_DELETE", Bundle()))
                        .build()
                    
                    return MediaSession.ConnectionResult.accept(
                        availableCommands,
                        connectionResult.availablePlayerCommands
                    )
                }
            })
            .build()

        // Create a MediaSessionCompat for notification integration
        mediaSessionCompat = MediaSessionCompat(this, "FreeAudio")

        // Update notification on player state changes
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updateNotificationState()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateNotificationState()
            }
        })

        // Request audio focus
        requestAudioFocus()
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
        if (player.isPlaying) {
            startForeground(NOTIFICATION_ID, createForegroundNotification())
        } else {
            stopForeground(false)
            notificationManager.notify(NOTIFICATION_ID, createForegroundNotification())
        }
    }

    private fun createForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(player.mediaMetadata.title ?: "FreeAudio")
            .setContentText(player.mediaMetadata.artist ?: "Playing audio")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(MediaStyle()
                .setShowActionsInCompactView(0, 1)
                .setMediaSession(mediaSessionCompat.sessionToken))
            .addAction(
                R.drawable.baseline_skip_previous_24,
                "Previous",
                createActionPendingIntent("ACTION_PREVIOUS")
            )
            .addAction(
                if (player.isPlaying) R.drawable.baseline_pause_24 else R.drawable.baseline_play_arrow_24,
                if (player.isPlaying) "Pause" else "Play",
                createActionPendingIntent(if (player.isPlaying) "ACTION_PAUSE" else "ACTION_PLAY")
            )
            .addAction(
                R.drawable.baseline_skip_next_24,
                "Next",
                createActionPendingIntent("ACTION_NEXT")
            )
            .build()
    }

    private fun createActionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, AudioService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_PLAY" -> {
                if (requestAudioFocus()) {
                    player.play()
                }
            }
            "ACTION_PAUSE" -> {
                player.pause()
                abandonAudioFocus()
            }
            "ACTION_NEXT" -> {
                player.seekToNext()
            }
            "ACTION_PREVIOUS" -> {
                player.seekToPrevious()
            }
            "ACTION_STOP" -> {
                player.stop()
                abandonAudioFocus()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        abandonAudioFocus()
        mediaSessionCompat.release()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    fun isTalkDownloaded(talk: Talk): Boolean {
        val file = File(filesDir, "${talk.id}.mp3")
        return file.exists()
    }

    suspend fun downloadTalk(talk: Talk) {
        // ... existing download logic ...
    }

    fun deleteTalk(talk: Talk) {
        val file = File(filesDir, "${talk.id}.mp3")
        file.delete()
    }

    private fun requestAudioFocus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AndroidAudioAttributes.Builder()
                .setContentType(AndroidAudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AndroidAudioAttributes.USAGE_MEDIA)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()

            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (playWhenAudioFocusGained) {
                    player.play()
                    playWhenAudioFocusGained = false
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                playWhenAudioFocusGained = false
                player.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                playWhenAudioFocusGained = player.isPlaying
                player.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Lower volume for notifications, etc.
                player.volume = 0.3f
            }
        }
    }

    fun playTalk(talk: Talk) {
        if (isTalkDownloaded(talk)) {
            val file = File(filesDir, "${talk.id}.mp3")
            val uri = Uri.fromFile(file)
            
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(talk.title)
                        .setArtist(talk.speaker ?: "Unknown Speaker")
                        .setDisplayTitle(talk.title)
                        .build()
                )
                .build()
            
            player.setMediaItem(mediaItem)
            player.prepare()
            
            if (requestAudioFocus()) {
                player.play()
            }
        }
    }
}
