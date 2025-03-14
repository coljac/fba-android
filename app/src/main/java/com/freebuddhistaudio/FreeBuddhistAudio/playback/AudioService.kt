package com.freebuddhistaudio.FreeBuddhistAudio.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.freebuddhistaudio.FreeBuddhistAudio.MainActivity
import com.freebuddhistaudio.FreeBuddhistAudio.data.Talk
import com.freebuddhistaudio.FreeBuddhistAudio.data.TalkRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private const val TAG = "AudioService"
private const val ROOT_ID = "root"
private const val RECENT_ID = "recent"
private const val FAVORITES_ID = "favorites"
private const val DOWNLOADS_ID = "downloads"
private const val ALL_TALKS_ID = "all_talks"
private const val COMMAND_FORCE_UPDATE_METADATA = "com.freebuddhistaudio.COMMAND_FORCE_UPDATE_METADATA"

// Constants for LibraryParams extras
private const val EXTRA_RECENT = "android.media.browse.CONTENT_STYLE_RECENT"
private const val EXTRA_OFFLINE = "android.media.browse.CONTENT_STYLE_OFFLINE"

class AudioService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibraryService.MediaLibrarySession? = null
    private var player: ExoPlayer? = null
    private lateinit var repository: TalkRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val directExecutor = Executors.newSingleThreadExecutor()
    
    // Cache for media items
    private val mediaItemsCache = mutableMapOf<List<MediaItem>, List<MediaItem>>()
    private val childrenCache = mutableMapOf<String, List<MediaItem>>()
    private val searchResults = mutableMapOf<String, List<MediaItem>>()
    private var resumptionMediaItems: MediaSession.MediaItemsWithStartPosition? = null

    override fun onCreate() {
        super.onCreate()
        
        repository = TalkRepository(this)
        
        // Create the player
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus= */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Create the media library session
        mediaLibrarySession = MediaLibraryService.MediaLibrarySession.Builder(
            this,
            player!!,
            LibrarySessionCallback()
        )
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
                        Log.d(TAG, "Player state: READY")
                    }
                    Player.STATE_ENDED -> {
                        // Playback finished
                        Log.d(TAG, "Player state: ENDED")
                    }
                    Player.STATE_BUFFERING -> {
                        // Loading
                        Log.d(TAG, "Player state: BUFFERING")
                    }
                    Player.STATE_IDLE -> {
                        // Nothing to play
                        Log.d(TAG, "Player state: IDLE")
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Handle playing state changes
                Log.d(TAG, "isPlaying changed: $isPlaying")
            }
            
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                Log.d(TAG, "Media metadata changed: ${mediaMetadata.title}")
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? {
        Log.d(TAG, "onGetSession: Controller connecting: ${controllerInfo.packageName}")
        return mediaLibrarySession
    }

    override fun onDestroy() {
        // Release the player first
        player?.release()
        player = null
        
        // Then release the media session
        mediaLibrarySession?.release()
        mediaLibrarySession = null
        
        super.onDestroy()
    }

    private inner class LibrarySessionCallback : MediaLibraryService.MediaLibrarySession.Callback {
        
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            Log.d(TAG, "onConnect: ${controller.packageName}")
            
            // Add custom command for updating metadata
            val customCommands = listOf(
                SessionCommand(COMMAND_FORCE_UPDATE_METADATA, Bundle())
            )
            
            // Get the default connection result
            val connectionResult = super.onConnect(session, controller)
            
            // Add our custom commands to the available commands
            val availableSessionCommands = connectionResult.availableSessionCommands.buildUpon()
            customCommands.forEach { command ->
                availableSessionCommands.add(command)
            }
            
            // Return the modified connection result
            return MediaSession.ConnectionResult.accept(
                availableSessionCommands.build(),
                connectionResult.availablePlayerCommands
            )
        }
        
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == COMMAND_FORCE_UPDATE_METADATA) {
                Log.d(TAG, "Received custom command to update metadata")
                
                val title = args.getString("title", "")
                val artist = args.getString("artist", "")
                val artworkUri = args.getString("artworkUri", "")
                
                Log.d(TAG, "Metadata update: title=$title, artist=$artist, artworkUri=$artworkUri")
                
                // Update the current media item with the new metadata
                player?.currentMediaItem?.let { currentItem ->
                    // Create new metadata
                    val updatedMetadata = MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setArtworkUri(Uri.parse(artworkUri))
                        .setAlbumTitle(currentItem.mediaMetadata.albumTitle)
                        .setDisplayTitle(title)
                        .setSubtitle(artist)
                        .setDescription("Buddhist talk by $artist")
                        .setIsPlayable(true)
                        .build()
                    
                    // Create new media item
                    val updatedItem = MediaItem.Builder()
                        .setMediaId(currentItem.mediaId)
                        .setUri(currentItem.localConfiguration?.uri)
                        .setMediaMetadata(updatedMetadata)
                        .build()
                    
                    // Replace the current media item with the updated one
                    val currentIndex = player?.currentMediaItemIndex ?: 0
                    player?.removeMediaItem(currentIndex)
                    player?.addMediaItem(currentIndex, updatedItem)
                }
                
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            
            return super.onCustomCommand(session, controller, customCommand, args)
        }
        
        override fun onGetLibraryRoot(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Log.d(TAG, "onGetLibraryRoot: ${browser.packageName}, params: ${params?.extras}")
            
            // Check if this is a request for recent items
            val isRecentRequest = params?.extras?.getBoolean(EXTRA_RECENT) == true
            val isOfflineRequest = params?.extras?.getBoolean(EXTRA_OFFLINE) == true
            
            val rootItem = if (isRecentRequest) {
                // Return recent plays as root for recent requests
                MediaItem.Builder()
                    .setMediaId(RECENT_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Recent Plays")
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .build()
                    )
                    .build()
            } else if (isOfflineRequest) {
                // Return downloads as root for offline requests
                MediaItem.Builder()
                    .setMediaId(DOWNLOADS_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Downloaded Talks")
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .build()
                    )
                    .build()
            } else {
                // Return main root for normal requests
                MediaItem.Builder()
                    .setMediaId(ROOT_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Free Buddhist Audio")
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .build()
                    )
                    .build()
            }
            
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }
        
        override fun onGetChildren(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            Log.d(TAG, "onGetChildren: parentId=$parentId, page=$page, pageSize=$pageSize")
            
            // For categories that need repository data, we need to handle them asynchronously
            if (parentId in listOf(RECENT_ID, FAVORITES_ID, DOWNLOADS_ID, ALL_TALKS_ID) || parentId.startsWith("talk_")) {
                // Launch a coroutine to fetch the data
                serviceScope.launch {
                    try {
                        val children = when (parentId) {
                            RECENT_ID -> {
                                // Get recent plays
                                val recentPlays = repository.getRecentPlays()
                                recentPlays.flatMap { talk ->
                                    createPlayableMediaItemsForTalk(talk)
                                }
                            }
                            FAVORITES_ID -> {
                                // Get favorite talks
                                val favorites = repository.getFavoriteTalks()
                                favorites.flatMap { talk ->
                                    createPlayableMediaItemsForTalk(talk)
                                }
                            }
                            DOWNLOADS_ID -> {
                                // Get downloaded talks
                                val downloads = repository.getDownloadedTalks()
                                downloads.flatMap { talk ->
                                    createPlayableMediaItemsForTalk(talk)
                                }
                            }
                            ALL_TALKS_ID -> {
                                // For now, just combine recent, favorites, and downloads
                                val allTalks = mutableListOf<Talk>()
                                allTalks.addAll(repository.getRecentPlays())
                                allTalks.addAll(repository.getFavoriteTalks())
                                allTalks.addAll(repository.getDownloadedTalks())
                                
                                // Remove duplicates
                                allTalks.distinctBy { it.id }.flatMap { talk ->
                                    createPlayableMediaItemsForTalk(talk)
                                }
                            }
                            else -> {
                                // Check if this is a talk ID
                                if (parentId.startsWith("talk_")) {
                                    val talkId = parentId.removePrefix("talk_")
                                    val talk = repository.getTalkById(talkId)
                                    talk?.let {
                                        createPlayableMediaItemsForTalk(it)
                                    } ?: emptyList()
                                } else {
                                    emptyList()
                                }
                            }
                        }
                        
                        // Notify the session about the children
                        session.notifyChildrenChanged(browser, parentId, children.size, params)
                        
                        // Cache the results for when getChildren is called again
                        childrenCache[parentId] = children
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting children for $parentId", e)
                        session.notifyChildrenChanged(browser, parentId, 0, params)
                    }
                }
                
                // Return the cached results if available, otherwise return empty list
                val cachedChildren = childrenCache[parentId] ?: emptyList()
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.copyOf(cachedChildren), params)
                )
            } else {
                // For static categories (ROOT_ID), we can return immediately
                val children = when (parentId) {
                    ROOT_ID -> {
                        // Top-level categories
                        listOf(
                            createBrowsableMediaItem(RECENT_ID, "Recent Plays"),
                            createBrowsableMediaItem(FAVORITES_ID, "Favorites"),
                            createBrowsableMediaItem(DOWNLOADS_ID, "Downloads"),
                            createBrowsableMediaItem(ALL_TALKS_ID, "All Talks")
                        )
                    }
                    else -> emptyList()
                }
                
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
                )
            }
        }
        
        override fun onSearch(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            Log.d(TAG, "onSearch: query=$query")
            
            // Start a search in the background
            serviceScope.launch {
                try {
                    // This is a simplified search implementation
                    // In a real app, you would search your database or API
                    val results = withContext(Dispatchers.IO) {
                        val allTalks = mutableListOf<Talk>()
                        allTalks.addAll(repository.getRecentPlays())
                        allTalks.addAll(repository.getFavoriteTalks())
                        allTalks.addAll(repository.getDownloadedTalks())
                        
                        // Filter talks that match the query
                        allTalks.distinctBy { it.id }.filter { talk ->
                            talk.title.contains(query, ignoreCase = true) ||
                            talk.speaker.contains(query, ignoreCase = true) ||
                            talk.blurb.contains(query, ignoreCase = true)
                        }
                    }
                    
                    // Convert results to MediaItems
                    val mediaItems = results.flatMap { talk ->
                        createPlayableMediaItemsForTalk(talk)
                    }
                    
                    // Notify the session about search results
                    session.notifySearchResultChanged(browser, query, mediaItems.size, params)
                    
                    // Cache the results for when getSearchResult is called
                    searchResults[query] = mediaItems
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error during search", e)
                    session.notifySearchResultChanged(browser, query, 0, params)
                }
            }
            
            // Return immediately, results will be delivered asynchronously
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }
        
        override fun onGetSearchResult(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            Log.d(TAG, "onGetSearchResult: query=$query, page=$page, pageSize=$pageSize")
            
            // Get the cached search results
            val results = searchResults[query] ?: emptyList()
            
            // Apply pagination
            val startIndex = page * pageSize
            val endIndex = minOf(startIndex + pageSize, results.size)
            
            val paginatedResults = if (startIndex < results.size) {
                results.subList(startIndex, endIndex)
            } else {
                emptyList()
            }
            
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(paginatedResults), params)
            )
        }
        
        override fun onPlaybackResumption(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            Log.d(TAG, "onPlaybackResumption: ${controller.packageName}")
            
            // We need to use runBlocking here because we need to return a result immediately
            // and the repository methods are suspend functions
            val items = runBlocking {
                try {
                    val recentPlays = repository.getRecentPlays()
                    if (recentPlays.isNotEmpty()) {
                        val mostRecentTalk = recentPlays.first()
                        
                        // Create media items for the talk
                        val mediaItems = createPlayableMediaItemsForTalk(mostRecentTalk)
                        
                        MediaSession.MediaItemsWithStartPosition(
                            mediaItems,
                            /* startIndex= */ 0,
                            /* startPositionMs= */ 0
                        )
                    } else {
                        MediaSession.MediaItemsWithStartPosition(
                            ImmutableList.of(),
                            /* startIndex= */ 0,
                            /* startPositionMs= */ 0
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during playback resumption", e)
                    MediaSession.MediaItemsWithStartPosition(
                        ImmutableList.of(),
                        /* startIndex= */ 0,
                        /* startPositionMs= */ 0
                    )
                }
            }
            
            return Futures.immediateFuture(items)
        }
        
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            Log.d(TAG, "onAddMediaItems: ${mediaItems.size} items")
            
            // For media items that need repository data, we need to handle them synchronously
            // since we need to return a result immediately
            val resolvedItems = runBlocking {
                mediaItems.map { item ->
                    val mediaId = item.mediaId
                    
                    if (mediaId.startsWith("talk_")) {
                        // Extract talk ID and track number
                        val parts = mediaId.split("_")
                        if (parts.size >= 3) {
                            val talkId = parts[1]
                            val trackNumber = parts[2].removePrefix("track_").toIntOrNull() ?: 0
                            
                            // Load the talk
                            val talk = repository.getTalkById(talkId)
                            if (talk != null && trackNumber < talk.tracks.size) {
                                val track = talk.tracks[trackNumber]
                                
                                // Check if we have a local file
                                val localPath = repository.getLocalTalkPath(talkId, track.number)
                                val uri = if (localPath != null) {
                                    Uri.parse("file://$localPath")
                                } else {
                                    Uri.parse(track.path)
                                }
                                
                                // Create a fully resolved media item
                                MediaItem.Builder()
                                    .setMediaId(item.mediaId)
                                    .setUri(uri)
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(track.title)
                                            .setArtist(talk.speaker)
                                            .setAlbumTitle("Free Buddhist Audio")
                                            .setArtworkUri(Uri.parse(talk.imageUrl))
                                            .setDisplayTitle(track.title)
                                            .setSubtitle(talk.speaker)
                                            .setDescription("Buddhist talk by ${talk.speaker}")
                                            .setIsPlayable(true)
                                            .build()
                                    )
                                    .build()
                            } else {
                                // Talk not found, return the original item
                                item
                            }
                        } else {
                            // Invalid format, return the original item
                            item
                        }
                    } else {
                        // Not a talk ID, return the original item
                        item
                    }
                }
            }
            
            return Futures.immediateFuture(resolvedItems)
        }
    }
    
    // Helper method to create a browsable media item
    private fun createBrowsableMediaItem(id: String, title: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }
    
    // Helper method to create playable media items for a talk
    private fun createPlayableMediaItemsForTalk(talk: Talk): List<MediaItem> {
        return talk.tracks.mapIndexed { index, track ->
            val localPath = repository.getLocalTalkPath(talk.id, track.number)
            val uri = if (localPath != null) {
                Uri.parse("file://$localPath")
            } else {
                Uri.parse(track.path)
            }
            
            MediaItem.Builder()
                .setMediaId("talk_${talk.id}_track_${track.number}")
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(talk.speaker)
                        .setAlbumTitle("Free Buddhist Audio")
                        .setArtworkUri(Uri.parse(talk.imageUrl))
                        .setDisplayTitle(track.title)
                        .setSubtitle(talk.speaker)
                        .setDescription("Buddhist talk by ${talk.speaker}")
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        }
    }
    
    // Public methods for direct control (used by AudioViewModel)
    
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
