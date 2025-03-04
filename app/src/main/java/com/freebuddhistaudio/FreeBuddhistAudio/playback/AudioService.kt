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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.freebuddhistaudio.FreeBuddhistAudio.MainActivity
import com.freebuddhistaudio.FreeBuddhistAudio.R
import java.net.HttpURLConnection
import java.net.URL

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
                Log.d(TAG, "Media item transition detected: ${mediaItem?.mediaId}")
                if (mediaItem != null) {
                    Log.d(TAG, "  Title: ${mediaItem.mediaMetadata.title}")
                    Log.d(TAG, "  Artist: ${mediaItem.mediaMetadata.artist}")
                    Log.d(TAG, "  Album: ${mediaItem.mediaMetadata.albumTitle}")
                }
                // Force immediate metadata update for the lock screen
                updateMediaMetadata()
                updateMediaSessionState()
                updateNotificationState()
            }
            
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateMediaSessionState()
                updateNotificationState()
            }
            
            // Add explicit metadata listener to ensure we capture all changes
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                Log.d(TAG, "Media metadata changed explicitly")
                Log.d(TAG, "  Title: ${mediaMetadata.title}")
                Log.d(TAG, "  Artist: ${mediaMetadata.artist}")
                Log.d(TAG, "  Album: ${mediaMetadata.albumTitle}")
                updateMediaMetadata()
                updateNotificationState()
            }
        })
        
        // Force an initial metadata update after setup
        if (player.currentMediaItem != null) {
            updateMediaMetadata()
        }
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
        
        // Log the exact values for debugging
        Log.d(TAG, "Updating Media Metadata for Media Session")
        Log.d(TAG, "  - MediaItem ID: ${currentItem.mediaId}")
        Log.d(TAG, "  - Title: ${mediaMetadata.title?.toString()}")
        Log.d(TAG, "  - Artist: ${mediaMetadata.artist?.toString()}")
        Log.d(TAG, "  - Album: ${mediaMetadata.albumTitle?.toString()}")
        Log.d(TAG, "  - Display Title: ${mediaMetadata.displayTitle?.toString()}")
        Log.d(TAG, "  - Subtitle: ${mediaMetadata.subtitle?.toString()}")
        Log.d(TAG, "  - Description: ${mediaMetadata.description?.toString()}")
        Log.d(TAG, "  - Artwork URI: ${mediaMetadata.artworkUri}")
        
        // Enhanced metadata for Android Auto and lock screen with direct values 
        // to avoid any CharSequence conversion issues
        val title = mediaMetadata.title?.toString() ?: "Unknown TitleNope"
        val artist = mediaMetadata.artist?.toString() ?: "Unknown Artist"
        val album = "Free Buddhist Audio"
        
        val metadataBuilder = MediaMetadataCompat.Builder()
            // Basic metadata for MediaSession
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, "Free Buddhist Audio")
            
            // Display metadata for lock screen and notifications
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, mediaMetadata.description?.toString() ?: "Buddhist Talk")
            
            // Other metadata
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, player.duration)
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, currentItem.mediaId)
            .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, (player.currentMediaItemIndex + 1).toLong())
            .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, player.mediaItemCount.toLong())
        
        // Try to load artwork
        try {
            val artworkUri = mediaMetadata.artworkUri
            if (artworkUri != null) {
                Log.d(TAG, "Attempting to load artwork from: $artworkUri")
                
                // For remote images, we need to download them in a background thread
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // First try to download and use the actual image
                        val bitmap = loadBitmapFromUri(artworkUri)
                        
                        if (bitmap != null) {
                            Log.d(TAG, "Successfully loaded artwork bitmap")
                            
                            // Update metadata on main thread
                            withContext(Dispatchers.Main) {
                                // Only update if the player is still active and on the same track
                                if (player.currentMediaItem?.mediaId == currentItem.mediaId) {
                                    val updatedBuilder = metadataBuilder
                                        .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                                        .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
                                        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artworkUri.toString())
                                        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artworkUri.toString())
                                    
                                    mediaSessionCompat?.setMetadata(updatedBuilder.build())
                                    
                                    // Also update notification with artwork
                                    updateNotificationState()
                                }
                            }
                        } else {
                            // Use default icon as fallback
                            withContext(Dispatchers.Main) {
                                setDefaultArtwork(metadataBuilder)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading artwork from URI", e)
                        
                        // Use default icon as fallback
                        withContext(Dispatchers.Main) {
                            setDefaultArtwork(metadataBuilder)
                        }
                    }
                }
            } else {
                // No artwork URI, use default
                setDefaultArtwork(metadataBuilder)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle artwork", e)
            // Use default artwork
            setDefaultArtwork(metadataBuilder)
        }
        
        // Set initial metadata (artwork may be updated later)
        mediaSessionCompat?.setMetadata(metadataBuilder.build())
    }
    
    private fun setDefaultArtwork(metadataBuilder: MediaMetadataCompat.Builder) {
        val bitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_notification)
        if (bitmap != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
            mediaSessionCompat?.setMetadata(metadataBuilder.build())
        }
    }
    
    /**
     * Force update metadata using explicit values from the ViewModel
     * This provides a direct way to set metadata when the player's metadata might be delayed
     */
    private fun forceUpdateMetadata(title: String, artist: String, artworkUriString: String?) {
        Log.d(TAG, "Force updating metadata with explicit values")
        Log.d(TAG, "  Title: $title")
        Log.d(TAG, "  Artist: $artist")
        Log.d(TAG, "  Album: Free Buddhist Audio")
        
        // Build metadata with explicit values
        val metadataBuilder = MediaMetadataCompat.Builder()
            // Basic metadata for MediaSession
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Free Buddhist Audio")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, "Free Buddhist Audio")
            
            // Display metadata for lock screen and notifications
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, "Buddhist Talk by $artist")
            
            // Other metadata
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, player.duration)
            
        if (artworkUriString != null) {
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artworkUriString)
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artworkUriString)
            
            // Attempt to load artwork in the background
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val artworkUri = Uri.parse(artworkUriString)
                    val bitmap = loadBitmapFromUri(artworkUri)
                    
                    if (bitmap != null) {
                        withContext(Dispatchers.Main) {
                            val updatedBuilder = MediaMetadataCompat.Builder(metadataBuilder.build())
                                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
                            
                            // Set updated metadata with artwork
                            mediaSessionCompat?.setMetadata(updatedBuilder.build())
                            
                            // Update notification to show artwork
                            updateNotificationState()
                            
                            Log.d(TAG, "Added artwork to forced metadata update")
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            setDefaultArtwork(metadataBuilder)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading artwork for forced metadata update", e)
                    withContext(Dispatchers.Main) {
                        setDefaultArtwork(metadataBuilder)
                    }
                }
            }
        } else {
            // No artwork URI, use default
            setDefaultArtwork(metadataBuilder)
        }
        
        // Set initial metadata even before artwork loads
        mediaSessionCompat?.setMetadata(metadataBuilder.build())
        
        // Update notification with new metadata
        updateNotificationState()
    }
    
    private suspend fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            // For http/https URIs
            if (uri.scheme == "http" || uri.scheme == "https") {
                val url = URL(uri.toString())
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 10000
                connection.doInput = true
                connection.connect()
                
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                    connection.disconnect()
                    bitmap
                } else {
                    connection.disconnect()
                    null
                }
            } 
            // For file URIs
            else if (uri.scheme == "file") {
                BitmapFactory.decodeFile(uri.path)
            } 
            // For content URIs
            else if (uri.scheme == "content") {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap from URI", e)
            null
        }
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
        // Get metadata directly from MediaSessionCompat for consistency
        val metadata = mediaSessionCompat?.controller?.metadata
        
        Log.d(TAG, "Creating Notification with MediaSessionCompat metadata:")
        if (metadata != null) {
            Log.d(TAG, "  - TITLE: ${metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE)}")
            Log.d(TAG, "  - ARTIST: ${metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)}")
            Log.d(TAG, "  - ALBUM: ${metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM)}")
            Log.d(TAG, "  - DISPLAY_TITLE: ${metadata.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE)}")
            Log.d(TAG, "  - DISPLAY_SUBTITLE: ${metadata.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE)}")
            Log.d(TAG, "  - Has Album Art: ${metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART) != null}")
        } else {
            Log.d(TAG, "  - No MediaSessionCompat metadata available")
        }
        
        // Use values from MediaSessionCompat metadata as primary source,
        // fallback to player's current media item as backup
        val currentItem = player.currentMediaItem
        
        // Clear, consistent values
        val title = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) 
            ?: currentItem?.mediaMetadata?.title?.toString() 
            ?: "Unknown TitleBoy"
            
        val artist = metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) 
            ?: currentItem?.mediaMetadata?.artist?.toString() 
            ?: "Unknown Speaker"
            
        // Get album art from session metadata
        val albumArt = metadata?.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
        
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
            
        // Build a standard media notification using our clear metadata values
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText("Free Buddhist Audio")
            .setSmallIcon(R.drawable.ic_notification)
            
        // Add album art if available
        if (albumArt != null) {
            builder.setLargeIcon(albumArt)
        } else {
            // Use app icon as fallback
            val defaultIcon = BitmapFactory.decodeResource(resources, R.drawable.ic_notification)
            if (defaultIcon != null) {
                builder.setLargeIcon(defaultIcon)
            }
        }
        
        builder.setContentIntent(
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
        
        // Check for explicit metadata sent from ViewModel
        val trackTitle = intent?.getStringExtra("EXTRA_TRACK_TITLE")
        val speakerName = intent?.getStringExtra("EXTRA_SPEAKER_NAME")
        val artworkUri = intent?.getStringExtra("EXTRA_ARTWORK_URI")
        
        if (trackTitle != null && speakerName != null) {
            Log.d(TAG, "Received explicit metadata in intent:")
            Log.d(TAG, "  Title: $trackTitle")
            Log.d(TAG, "  Artist: $speakerName")
            Log.d(TAG, "  Artwork URI: $artworkUri")
            
            // Force metadata update using these values
            forceUpdateMetadata(trackTitle, speakerName, artworkUri)
        }
        
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
                
                // When starting playback, ensure we have updated metadata
                updateMediaMetadata()
                updateMediaSessionState()
                updateNotificationState()
                
                // Log current metadata for debugging
                val currentItem = player.currentMediaItem
                if (currentItem != null) {
                    Log.d(TAG, "ACTION_PLAY with metadata:")
                    Log.d(TAG, "  Title: ${currentItem.mediaMetadata.title}")
                    Log.d(TAG, "  Artist: ${currentItem.mediaMetadata.artist}")
                    Log.d(TAG, "  Album: ${currentItem.mediaMetadata.albumTitle}")
                }
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
