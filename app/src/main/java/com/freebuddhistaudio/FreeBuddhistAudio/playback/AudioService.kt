package com.freebuddhistaudio.FreeBuddhistAudio.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.freebuddhistaudio.FreeBuddhistAudio.MainActivity

class AudioService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        
        // Create the player
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus= */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Create the media session
        mediaSession = MediaSession.Builder(this, player!!)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        // Add player listener for state changes
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        // Ready to play
                    }
                    Player.STATE_ENDED -> {
                        // Playback finished
                    }
                    Player.STATE_BUFFERING -> {
                        // Loading
                    }
                    Player.STATE_IDLE -> {
                        // Nothing to play
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Handle playing state changes
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        // Release the player first
        player?.release()
        player = null
        
        // Then release the media session
        mediaSession?.release()
        mediaSession = null
        
        super.onDestroy()
    }

    fun loadAndPlay(mediaUri: String) {
        player?.run {
            setMediaItem(MediaItem.fromUri(mediaUri))
            prepare()
            play()
        }
    }

    fun togglePlayPause() {
        player?.run {
            if (isPlaying) {
                pause()
            } else {
                play()
            }
        }
    }

    fun stop() {
        player?.stop()
    }

    fun seekTo(position: Long) {
        player?.seekTo(position)
    }
}
