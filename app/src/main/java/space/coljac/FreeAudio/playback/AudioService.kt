package com.freebuddhistaudio.FreeBuddhistAudio.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.freebuddhistaudio.FreeBuddhistAudio.MainActivity
import com.freebuddhistaudio.FreeBuddhistAudio.R

private const val NOTIFICATION_ID = 123
private const val CHANNEL_ID = "FreeAudio_playback_channel"
private const val TAG = "AudioService"

@UnstableApi
class AudioService : MediaBrowserServiceCompat() {
    private var mediaSession: MediaSession? = null
    private var mediaSessionCompat: MediaSessionCompat? = null  // For compatibility
    private lateinit var player: ExoPlayer
    private lateinit var notificationManager: NotificationManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // Root ID for Android Auto media browser
    private val AUTO_ROOT_ID = "root_for_auto"
    private val MEDIA_ROOT_ID = "media_root_id"
    private val BROWSABLE_ROOT = "/__BROWSABLE_ROOT__"
    private val EMPTY_ROOT = "/__EMPTY_ROOT__"
    private val RECENT_ROOT = "/Recent"
    private val ALL_TALKS_ROOT = "/AllTalks"
    private val FAVORITES_ROOT = "/Favorites"
    private val DOWNLOADS_ROOT = "/Downloads"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AudioService onCreate")
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
            .setHandleAudioBecomingNoisy(true) // Handle headphone disconnections
            .build()

        // Create MediaSession with a complete player configuration
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Create a custom callback to handle playback resumption
        val callback = object : MediaSession.Callback {
            override fun onPlaybackResumption(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): com.google.common.util.concurrent.ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                Log.d(TAG, "Media3 onPlaybackResumption from ${controller.packageName}")
                
                // Start playback when requested by external controllers
                if (player.playbackState == Player.STATE_IDLE) {
                    player.prepare()
                }
                player.play()
                
                // Return a successful result with current media items
                val mediaItems = mutableListOf<MediaItem>()
                for (i in 0 until player.mediaItemCount) {
                    mediaItems.add(player.getMediaItemAt(i))
                }
                
                val result = MediaSession.MediaItemsWithStartPosition(
                    mediaItems,
                    player.currentMediaItemIndex,
                    player.currentPosition
                )
                
                return com.google.common.util.concurrent.Futures.immediateFuture(result)
            }
        }
        
        // Set up MediaSession for Media3 with custom callback
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent)
            .setId("FreeAudio_media_session") // Use a unique ID
            .setCallback(callback)
            .build()
            
        // Set up MediaSessionCompat for better system integration
        val mediaButtonReceiver = ComponentName(this, MediaButtonReceiver::class.java)
        mediaSessionCompat = MediaSessionCompat(this, "FreeAudio_media_session", mediaButtonReceiver, null).apply {
            // Set flags to allow media buttons and transport controls
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            
            // Register callbacks to handle media buttons
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    Log.d(TAG, "MediaSessionCompat.Callback - onPlay()")
                    
                    // Log detailed player state for debugging
                    Log.d(TAG, "Before play action - playWhenReady: ${player.playWhenReady}, " +
                          "playbackState: ${playbackStateToString(player.playbackState)}, " +
                          "isPlaying: ${player.isPlaying}, " +
                          "mediaItemCount: ${player.mediaItemCount}")
                    
                    // Handle different player states
                    when (player.playbackState) {
                        Player.STATE_IDLE -> {
                            Log.d(TAG, "Player is IDLE, preparing player first")
                            player.prepare()
                        }
                        Player.STATE_ENDED -> {
                            Log.d(TAG, "Player is ENDED, seeking to start and preparing")
                            player.seekTo(0)
                            player.prepare()
                        }
                        Player.STATE_BUFFERING -> {
                            Log.d(TAG, "Player is BUFFERING, attempting to play anyway")
                        }
                        Player.STATE_READY -> {
                            Log.d(TAG, "Player is READY, can play directly")
                        }
                    }
                    
                    if (player.mediaItemCount <= 0) {
                        Log.d(TAG, "⚠️ No media items available, can't play")
                        return
                    }
                    
                    // Use multiple approaches to ensure playback starts
                    try {
                        // Method 1: Use play()
                        player.play()
                        Log.d(TAG, "Called player.play()")
                        
                        // Method 2: Set playWhenReady = true
                        player.playWhenReady = true
                        Log.d(TAG, "Set player.playWhenReady = true")
                        
                        // Wait a moment to make sure state changes are applied
                        Handler(Looper.getMainLooper()).postDelayed({
                            // Method 3: Force through the player API if still not playing
                            if (!player.isPlaying && player.playbackState == Player.STATE_READY) {
                                Log.d(TAG, "Player still not playing, trying direct Player API control")
                                player.play()
                            }
                            
                            // Update states in media session and notification
                            updateMediaSessionState()
                            updateNotificationState()
                            
                            Log.d(TAG, "Updated state after play: isPlaying=${player.isPlaying}, " +
                                  "playWhenReady=${player.playWhenReady}, " +
                                  "playbackState=${playbackStateToString(player.playbackState)}")
                            
                            // Broadcast the playback state to ensure the whole app knows about it
                            val intent = Intent("ACTION_PLAYBACK_STATE_CHANGED")
                            intent.putExtra("IS_PLAYING", player.isPlaying)
                            sendBroadcast(intent)
                        }, 200)
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting playback", e)
                    }
                }
                
                override fun onPause() {
                    Log.d(TAG, "MediaSessionCompat.Callback - onPause()")
                    
                    // Log detailed player state for debugging
                    Log.d(TAG, "Before pause action - playWhenReady: ${player.playWhenReady}, " +
                          "playbackState: ${playbackStateToString(player.playbackState)}, " +
                          "isPlaying: ${player.isPlaying}")
                    
                    try {
                        // Use multiple approaches to ensure playback pauses
                        
                        // Method 1: Use pause()
                        player.pause()
                        Log.d(TAG, "Called player.pause()")
                        
                        // Method 2: Set playWhenReady = false
                        player.playWhenReady = false
                        Log.d(TAG, "Set player.playWhenReady = false")
                        
                        // Wait a moment to make sure state changes are applied
                        Handler(Looper.getMainLooper()).postDelayed({
                            // Update states in media session and notification
                            updateMediaSessionState()
                            updateNotificationState()
                            
                            Log.d(TAG, "Updated state after pause: isPlaying=${player.isPlaying}, " +
                                  "playWhenReady=${player.playWhenReady}, " +
                                  "playbackState=${playbackStateToString(player.playbackState)}")
                            
                            // Broadcast the playback state to ensure the whole app knows about it
                            val intent = Intent("ACTION_PLAYBACK_STATE_CHANGED")
                            intent.putExtra("IS_PLAYING", player.isPlaying)
                            sendBroadcast(intent)
                        }, 200)
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Error pausing playback", e)
                    }
                }
                
                override fun onSkipToNext() {
                    Log.d(TAG, "MediaSessionCompat.Callback - onSkipToNext()")
                    player.seekToNextMediaItem()
                    updateMediaSessionState()
                    updateNotificationState()
                }
                
                override fun onSkipToPrevious() {
                    Log.d(TAG, "MediaSessionCompat.Callback - onSkipToPrevious()")
                    player.seekToPreviousMediaItem()
                    updateMediaSessionState()
                    updateNotificationState()
                }
                
                override fun onStop() {
                    Log.d(TAG, "MediaSessionCompat.Callback - onStop()")
                    player.stop()
                    updateMediaSessionState()
                    updateNotificationState()
                }
                
                override fun onFastForward() {
                    Log.d(TAG, "MediaSessionCompat.Callback - onFastForward()")
                    player.seekForward()
                    updateMediaSessionState()
                    updateNotificationState()
                }
                
                override fun onRewind() {
                    Log.d(TAG, "MediaSessionCompat.Callback - onRewind()")
                    player.seekBack()
                    updateMediaSessionState()
                    updateNotificationState()
                }
                
                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    Log.d(TAG, "MediaSessionCompat.Callback - onMediaButtonEvent(): ${mediaButtonEvent.action}")
                    // Let the superclass handle it, which will decode the KeyEvent and call the appropriate method
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
            
            // Set initial activity intent
            setSessionActivity(sessionActivityPendingIntent)
        }
        
        // Start the session
        mediaSessionCompat?.isActive = true

        // Update notification and media session state
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updateMediaSessionState()
                updateNotificationState()
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateMediaSessionState()
                updateNotificationState()
            }
            
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateMediaMetadata()
                updateMediaSessionState()
                updateNotificationState()
            }
            
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateMediaSessionState()
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

    private fun updateMediaSessionState() {
        if (mediaSessionCompat == null) return
        
        // Log the current player state for debugging
        Log.d(TAG, "Updating MediaSession state: " +
              "playWhenReady=${player.playWhenReady}, " +
              "isPlaying=${player.isPlaying}, " +
              "playbackState=${playbackStateToString(player.playbackState)}")
        
        // Map ExoPlayer state to PlaybackStateCompat
        val state = when {
            player.playbackState == Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            player.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            player.playWhenReady && player.playbackState == Player.STATE_READY -> PlaybackStateCompat.STATE_PLAYING
            player.playbackState == Player.STATE_ENDED -> PlaybackStateCompat.STATE_STOPPED
            player.playbackState == Player.STATE_IDLE -> PlaybackStateCompat.STATE_NONE
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        
        // Log the mapped state
        Log.d(TAG, "Mapped to PlaybackStateCompat: ${playbackStateCompatToString(state)}")
        
        // Define available actions
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO
        
        // Create and set playback state
        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, player.currentPosition, player.playbackParameters.speed)
            .setActions(actions)
            .build()
        
        mediaSessionCompat?.setPlaybackState(playbackState)
    }
    
    // Helper method to convert ExoPlayer state to string for debugging
    private fun playbackStateToString(state: Int): String {
        return when (state) {
            Player.STATE_IDLE -> "STATE_IDLE"
            Player.STATE_BUFFERING -> "STATE_BUFFERING"
            Player.STATE_READY -> "STATE_READY"
            Player.STATE_ENDED -> "STATE_ENDED"
            else -> "UNKNOWN"
        }
    }
    
    // Helper method to convert PlaybackStateCompat state to string for debugging
    private fun playbackStateCompatToString(state: Int): String {
        return when (state) {
            PlaybackStateCompat.STATE_NONE -> "STATE_NONE"
            PlaybackStateCompat.STATE_STOPPED -> "STATE_STOPPED"
            PlaybackStateCompat.STATE_PAUSED -> "STATE_PAUSED"
            PlaybackStateCompat.STATE_PLAYING -> "STATE_PLAYING"
            PlaybackStateCompat.STATE_BUFFERING -> "STATE_BUFFERING"
            else -> "UNKNOWN ($state)"
        }
    }
    
    private fun updateMediaMetadata() {
        if (mediaSessionCompat == null) return
        
        val currentItem = player.currentMediaItem ?: return
        val mediaMetadata = currentItem.mediaMetadata
        
        // Enhanced metadata for Android Auto
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, mediaMetadata.title?.toString() ?: "Unknown Title")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, mediaMetadata.artist?.toString() ?: "Unknown Artist")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, mediaMetadata.albumTitle?.toString() ?: "Free Buddhist Audio")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, "Free Buddhist Audio")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, mediaMetadata.title?.toString() ?: "Unknown Title")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, mediaMetadata.artist?.toString() ?: "Unknown Artist")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, mediaMetadata.description?.toString() ?: "Buddhist Talk")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, player.duration)
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, currentItem.mediaId)
            .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, (player.currentMediaItemIndex + 1).toLong())
            .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, player.mediaItemCount.toLong())
        
        // Try to load artwork
        try {
            val artworkUri = mediaMetadata.artworkUri
            if (artworkUri != null) {
                // For Android Auto, it's important to have album art
                val bitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_notification)
                if (bitmap != null) {
                    metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                    metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
                    metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artworkUri.toString())
                    metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artworkUri.toString())
                }
            } else {
                // Always provide a default artwork for Auto display
                val bitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_notification)
                if (bitmap != null) {
                    metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                    metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load artwork", e)
            // Always provide a default artwork for Auto display
            val bitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_notification)
            if (bitmap != null) {
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
            }
        }
        
        // Set metadata
        mediaSessionCompat?.setMetadata(metadataBuilder.build())
    }
    
    private fun updateNotificationState() {
        if (mediaSessionCompat == null) return
        
        val notification = createNotification()
        if (player.isPlaying) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_DETACH)
            } else {
                stopForeground(false)
            }
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }
    
    private fun createNotification(): Notification {
        val currentItem = player.currentMediaItem
        val title = currentItem?.mediaMetadata?.title ?: "Free Buddhist Audio"
        val artist = currentItem?.mediaMetadata?.artist ?: "Unknown"
        
        // Create play intent
        val playIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AudioService::class.java).setAction("ACTION_PLAY"),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // Create pause intent
        val pauseIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, AudioService::class.java).setAction("ACTION_PAUSE"),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // Create next track intent
        val nextIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, AudioService::class.java).setAction("ACTION_NEXT"),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // Create previous track intent
        val prevIntent = PendingIntent.getService(
            this,
            4,
            Intent(this, AudioService::class.java).setAction("ACTION_PREVIOUS"),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // Get media buttons receiver intent
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
        mediaButtonIntent.setClass(this, MediaButtonReceiver::class.java)
        val pendingMediaButtonIntent = PendingIntent.getBroadcast(
            this, 0, mediaButtonIntent, PendingIntent.FLAG_IMMUTABLE
        )
        
        // Create media style
        val mediaStyle = MediaStyle()
            .setMediaSession(mediaSessionCompat?.sessionToken) // Use compat session token
            .setShowActionsInCompactView(0, 1, 2) // Show first 3 actions in compact view
            .setShowCancelButton(true)
            .setCancelButtonIntent(
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_STOP
                )
            )
        
        // Build a standard media notification
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText(if (player.isPlaying) "Playing" else "Paused")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setOngoing(player.isPlaying)
            .setDeleteIntent(
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_STOP
                )
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(mediaStyle)
            
        // Add play/pause action 
        builder.addAction(
            NotificationCompat.Action(
                if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (player.isPlaying) "Pause" else "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    if (player.isPlaying) PlaybackStateCompat.ACTION_PAUSE else PlaybackStateCompat.ACTION_PLAY
                )
            )
        )
        
        // Add previous action
        builder.addAction(
            NotificationCompat.Action(
                R.drawable.ic_play, 
                "Previous",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, 
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
            )
        )
        
        // Add next action
        builder.addAction(
            NotificationCompat.Action(
                R.drawable.ic_play, 
                "Next",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, 
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                )
            )
        )
        
        return builder.build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    // For Media3 compatibility
    fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: intent=${intent?.action}")
        
        // Handle media button events from MediaButtonReceiver
        MediaButtonReceiver.handleIntent(mediaSessionCompat, intent)
        
        // Handle custom actions
        when (intent?.action) {
            "ACTION_PLAY" -> {
                Log.d(TAG, "Handling ACTION_PLAY")
                if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                    player.prepare()
                }
                player.play()
                updateMediaSessionState()
                updateNotificationState()
            }
            "ACTION_PAUSE" -> {
                Log.d(TAG, "Handling ACTION_PAUSE")
                player.pause()
                updateMediaSessionState()
                updateNotificationState()
            }
            "ACTION_SKIP_FORWARD" -> {
                Log.d(TAG, "Handling ACTION_SKIP_FORWARD")
                val newPosition = player.currentPosition + 10000
                player.seekTo(newPosition)
                updateMediaSessionState()
                updateNotificationState()
            }
            "ACTION_SKIP_BACKWARD" -> {
                Log.d(TAG, "Handling ACTION_SKIP_BACKWARD")
                val newPosition = (player.currentPosition - 10000).coerceAtLeast(0)
                player.seekTo(newPosition)
                updateMediaSessionState()
                updateNotificationState()
            }
            "ACTION_NEXT" -> {
                Log.d(TAG, "Handling ACTION_NEXT")
                player.seekToNextMediaItem()
                updateMediaSessionState()
                updateNotificationState()
            }
            "ACTION_PREVIOUS" -> {
                Log.d(TAG, "Handling ACTION_PREVIOUS")
                player.seekToPreviousMediaItem()
                updateMediaSessionState()
                updateNotificationState()
            }
            Intent.ACTION_MEDIA_BUTTON -> {
                Log.d(TAG, "Handling ACTION_MEDIA_BUTTON")
                // This is handled by MediaButtonReceiver.handleIntent above
            }
        }
        
        return START_STICKY // Ensure the service stays running
    }

    override fun onDestroy() {
        Log.d(TAG, "AudioService onDestroy")
        
        // Release MediaSessionCompat
        mediaSessionCompat?.run {
            isActive = false
            release()
            mediaSessionCompat = null
        }
        
        // Release Media3 MediaSession
        mediaSession?.run {
            release()
            mediaSession = null
        }
        
        // Release ExoPlayer
        player.release()
        
        super.onDestroy()
    }
    
    inner class MediaSessionCallback {
        // Basic callback methods for handling media session commands
        fun onPlay() {
            player.play()
        }
        
        fun onPause() {
            player.pause()
        }
        
        fun onSkipToNext() {
            player.seekToNextMediaItem()
        }
        
        fun onSkipToPrevious() {
            player.seekToPreviousMediaItem()
        }
        
        fun onSeekForward() {
            player.seekForward()
        }
        
        fun onSeekBackward() {
            player.seekBack()
        }
    }
    
    /**
     * MediaBrowserServiceCompat implementation for Android Auto
     */
    // Implementation of MediaBrowserServiceCompat methods for Android Auto
override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
    // Check if this is a connection from Android Auto
    val isAutomotive = rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT) ?: false ||
                       rootHints?.getBoolean(BrowserRoot.EXTRA_OFFLINE) ?: false ||
                       rootHints?.getBoolean(BrowserRoot.EXTRA_SUGGESTED) ?: false
    
    Log.d(TAG, "onGetRoot: clientPackage=$clientPackageName, clientUid=$clientUid, isAutomotive=$isAutomotive")
    
    return if (isAutomotive || isCarPackage(clientPackageName)) {
        // Return a browsable root for Android Auto
        BrowserRoot(AUTO_ROOT_ID, null)
    } else {
        // For regular app connections
        BrowserRoot(MEDIA_ROOT_ID, null)
    }
}
override fun onLoadChildren(parentId: String, result: Result<List<MediaBrowserCompat.MediaItem>>) {
    Log.d(TAG, "onLoadChildren: parentId=$parentId")
    
    // Detach result to send it later when data is available
    result.detach()
    
    when (parentId) {
        // Root level for Auto - provide main categories
        AUTO_ROOT_ID -> {
            val items = listOf(
                createBrowsableMediaItem(RECENT_ROOT, "Recent Talks", null),
                createBrowsableMediaItem(ALL_TALKS_ROOT, "All Talks", null),
                createBrowsableMediaItem(FAVORITES_ROOT, "Favorites", null),
                createBrowsableMediaItem(DOWNLOADS_ROOT, "Downloaded Talks", null)
            )
            result.sendResult(items)
        }
        
        MEDIA_ROOT_ID -> {
            // For regular app browsing
            val items = listOf(
                createBrowsableMediaItem(ALL_TALKS_ROOT, "All Talks", null)
            )
            result.sendResult(items)
        }
        
        // Implement other category browsing based on parentId
        RECENT_ROOT -> {
            // TODO: Load recent talks
            result.sendResult(emptyList())
        }
        
        ALL_TALKS_ROOT -> {
            // TODO: Load all talks
            result.sendResult(emptyList())
        }
        
        FAVORITES_ROOT -> {
            // TODO: Load favorites
            result.sendResult(emptyList())
        }
        
        DOWNLOADS_ROOT -> {
            // TODO: Load downloads
            result.sendResult(emptyList())
        }
        
        else -> {
            // Unknown parentId
            result.sendResult(emptyList())
        }
    }
}

override fun onSearch(query: String, extras: Bundle?, result: Result<List<MediaBrowserCompat.MediaItem>>) {
    Log.d(TAG, "onSearch: query=$query")
    
    // Detach result to send it later when search results are available
    result.detach()
    
    // TODO: Implement search
    result.sendResult(emptyList())
}
    private fun createBrowsableMediaItem(id: String, title: String, iconUri: Uri?): MediaBrowserCompat.MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setIconUri(iconUri)
            .build()
        
        return MediaBrowserCompat.MediaItem(
            description,
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )
    }
    
    private fun createPlayableMediaItem(id: String, title: String, artist: String, 
                                       albumTitle: String, iconUri: Uri?, mediaUri: Uri?): MediaBrowserCompat.MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(artist)
            .setDescription(albumTitle)
            .setIconUri(iconUri)
            .setMediaUri(mediaUri)
            .build()
        
        return MediaBrowserCompat.MediaItem(
            description,
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
    }
    
    private fun isCarPackage(packageName: String): Boolean {
        // Known Android Auto related packages
        return packageName.contains("android.car") || 
               packageName.contains("com.google.android.projection.gearhead") ||
               packageName == "com.google.android.wearable.app" || 
               packageName == "com.android.bluetooth"
    }
}
