package space.coljac.FreeAudio.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import space.coljac.FreeAudio.data.SearchResponse
import space.coljac.FreeAudio.data.SearchState
import space.coljac.FreeAudio.data.Talk
import space.coljac.FreeAudio.data.Track
import space.coljac.FreeAudio.network.FBAService
import space.coljac.FreeAudio.data.TalkRepository
import java.io.File
import android.net.Uri

private const val TAG = "AudioViewModel"

class AudioViewModel(application: Application) : AndroidViewModel(application) {
    private val fbaService = FBAService()
    private val repository = TalkRepository(application)
    private val _searchState = MutableStateFlow<SearchState>(SearchState.Empty)
    val searchState: StateFlow<SearchState> = _searchState
    
    private val _isUpdatingSearchResults = MutableStateFlow(false)
    val isUpdatingSearchResults: StateFlow<Boolean> = _isUpdatingSearchResults
    
    private var player: ExoPlayer? = null
    
    private val _currentTalk = MutableStateFlow<Talk?>(null)
    val currentTalk: StateFlow<Talk?> = _currentTalk

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress

    private val _isDownloaded = MutableStateFlow(false)
    val isDownloaded: StateFlow<Boolean> = _isDownloaded

    private val _downloadedTalks = MutableStateFlow<List<Talk>>(emptyList())
    val downloadedTalks: StateFlow<List<Talk>> = _downloadedTalks

    private val _recentPlays = MutableStateFlow<List<Talk>>(emptyList())
    val recentPlays: StateFlow<List<Talk>> = _recentPlays
    
    private val _favoriteTalks = MutableStateFlow<List<Talk>>(emptyList())
    val favoriteTalks: StateFlow<List<Talk>> = _favoriteTalks

    data class PlaybackState(
        val isPlaying: Boolean = false,
        val currentTrackIndex: Int = 0,
        val position: Long = 0,
        val duration: Long = 0,
        val currentTrack: Track? = null
    )

    init {
        loadDownloadedTalks()
        loadFavorites()
    }

    private fun loadDownloadedTalks() {
        viewModelScope.launch {
            _downloadedTalks.value = repository.getDownloadedTalks()
        }
    }

    fun search(query: String) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Searching for: $query")
                _searchState.value = SearchState.Loading
                val searchResults = fbaService.search(query)
                Log.d(TAG, "Got ${searchResults.total} results")
                
                // First display the initial results
                _searchState.value = SearchState.Success(searchResults)
                
                // Then update track information in the background
                _isUpdatingSearchResults.value = true
                
                try {
                    // Get accurate track counts and durations from talk details
                    val updatedResults = searchResults.results.map { talk ->
                        try {
                            // Try to get detailed information for each talk
                            val detailedTalk = repository.getTalkById(talk.id)
                            if (detailedTalk != null && detailedTalk.tracks.isNotEmpty()) {
                                Log.d(TAG, "Updated track count for ${talk.id}: was ${talk.tracks.size}, now ${detailedTalk.tracks.size}")
                                detailedTalk
                            } else {
                                talk
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to get detailed info for talk ${talk.id}", e)
                            talk
                        }
                    }
                    
                    val updatedResponse = SearchResponse(searchResults.total, updatedResults)
                    _searchState.value = SearchState.Success(updatedResponse)
                } finally {
                    _isUpdatingSearchResults.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Search error", e)
                _searchState.value = SearchState.Error(e.message ?: "Unknown error")
                _isUpdatingSearchResults.value = false
            }
        }
    }
    
    fun playTalk(talk: Talk, startTrackIndex: Int = 0) {
        setCurrentTalk(talk)
        initializePlayer()
        
        player?.run {
            val mediaItems = talk.tracks.map { track ->
                val localPath = repository.getLocalTalkPath(talk.id, track.number)
                val uri = if (localPath != null) {
                    Uri.parse("file://$localPath")
                } else {
                    Uri.parse(track.path)
                }
                
                // Create rich metadata for the media session and lock screen controls
                MediaItem.Builder()
                    .setUri(uri)
                    .setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(talk.speaker)
                            .setAlbumTitle(talk.title)
                            .setArtworkUri(Uri.parse(talk.imageUrl))
                            .setDisplayTitle(track.title)
                            .setSubtitle(talk.speaker)
                            // Note: We're keeping it simpler for compatibility
                            .build()
                    )
                    .build()
            }
            
            // Set media items and prepare the player
            setMediaItems(mediaItems)
            
            // Set the initial track (only if we have tracks)
            if (talk.tracks.isNotEmpty()) {
                val validTrackIndex = startTrackIndex.coerceIn(0, talk.tracks.size - 1)
                if (validTrackIndex > 0) {
                    seekTo(validTrackIndex, 0)
                }
                
                // Update current track in playback state
                _playbackState.value = _playbackState.value.copy(
                    currentTrackIndex = validTrackIndex,
                    currentTrack = talk.tracks[validTrackIndex]
                )
            }
            
            prepare()
            
            // Start audio service explicitly to ensure media playback controls work
            startAudioService()
        }
        
        viewModelScope.launch {
            repository.addToRecentPlays(talk)
            _recentPlays.value = repository.getRecentPlays()
        }
    }
    
    private fun startAudioService() {
        val context = getApplication<Application>().applicationContext
        val intent = Intent(context, Class.forName("space.coljac.FreeAudio.playback.AudioService"))
        context.startService(intent)
    }
    
    fun setCurrentTalk(talk: Talk) {
        _currentTalk.value = talk
        viewModelScope.launch {
            _isDownloaded.value = repository.isDownloaded(talk.id)
        }
    }

    fun togglePlayPause() {
        player?.let {
            // Start the service first to ensure it's running
            startAudioService()
            
            val context = getApplication<Application>().applicationContext
            val intent = Intent(context, Class.forName("space.coljac.FreeAudio.playback.AudioService"))
            
            if (it.isPlaying) {
                // Send pause command to the service
                intent.action = "ACTION_PAUSE"
                context.startService(intent)
                
                it.pause()
                _playbackState.value = _playbackState.value.copy(isPlaying = false)
            } else {
                // Send play command to the service
                intent.action = "ACTION_PLAY"
                context.startService(intent)
                
                it.play()
                _playbackState.value = _playbackState.value.copy(isPlaying = true)
            }
            
            // Update all aspects of playback state for consistency
            updatePlaybackState()
        }
    }
    
    fun playTrack(trackIndex: Int) {
        _currentTalk.value?.let { talk ->
            // First check if there are any tracks to play
            if (talk.tracks.isEmpty()) {
                Log.w(TAG, "Attempted to play track but talk has no tracks")
                return@let
            }
            
            if (trackIndex >= 0 && trackIndex < talk.tracks.size) {
                // If player is already initialized with this talk
                if (player?.mediaItemCount == talk.tracks.size) {
                    player?.seekTo(trackIndex, 0)
                    player?.play()
                    
                    // Send play command to service
                    val context = getApplication<Application>().applicationContext
                    val intent = Intent(context, Class.forName("space.coljac.FreeAudio.playback.AudioService"))
                    intent.action = "ACTION_PLAY"
                    context.startService(intent)
                    
                    updatePlaybackState()
                } else {
                    // Initialize player with this talk starting at the selected track
                    playTalk(talk, trackIndex)
                }
            } else {
                Log.w(TAG, "Invalid track index: $trackIndex (valid range is 0-${talk.tracks.size - 1})")
            }
        }
    }

    fun seekForward() {
        startAudioService()
        
        // Send command to service
        val context = getApplication<Application>().applicationContext
        val intent = Intent(context, Class.forName("space.coljac.FreeAudio.playback.AudioService"))
        intent.action = "ACTION_SKIP_FORWARD"
        context.startService(intent)
        
        // Update local player - seek forward 10 seconds
        player?.let { 
            val newPosition = it.currentPosition + 10000
            it.seekTo(newPosition)
        }
        updatePlaybackState()
    }

    fun seekBackward() {
        startAudioService()
        
        // Send command to service
        val context = getApplication<Application>().applicationContext
        val intent = Intent(context, Class.forName("space.coljac.FreeAudio.playback.AudioService"))
        intent.action = "ACTION_SKIP_BACKWARD"
        context.startService(intent)
        
        // Update local player - seek backward 10 seconds
        player?.let {
            val newPosition = (it.currentPosition - 10000).coerceAtLeast(0)
            it.seekTo(newPosition)
        }
        updatePlaybackState()
    }

    fun skipToNextTrack() {
        startAudioService()
        
        // Send command to service
        val context = getApplication<Application>().applicationContext
        val intent = Intent(context, Class.forName("space.coljac.FreeAudio.playback.AudioService"))
        intent.action = "ACTION_NEXT"
        context.startService(intent)
        
        // Update local player
        player?.seekToNextMediaItem()
        updatePlaybackState()
    }

    fun skipToPreviousTrack() {
        startAudioService()
        
        // Send command to service
        val context = getApplication<Application>().applicationContext
        val intent = Intent(context, Class.forName("space.coljac.FreeAudio.playback.AudioService"))
        intent.action = "ACTION_PREVIOUS"
        context.startService(intent)
        
        // Update local player
        player?.seekToPreviousMediaItem()
        updatePlaybackState()
    }

    fun downloadTalk(talk: Talk) {
        // Don't allow downloading if already downloading
        if (_downloadProgress.value != null) return

        viewModelScope.launch {
            try {
                // Set initial progress to indicate download has started
                _downloadProgress.value = 0f
                
                // Show "Downloading..." immediately
                Log.d(TAG, "Starting download for talk: ${talk.id}")
                
                // Make sure the current talk is set without triggering playback
                // This is important to avoid the issue where downloading starts playback
                if (_currentTalk.value?.id != talk.id) {
                    _currentTalk.value = talk
                    _isDownloaded.value = repository.isDownloaded(talk.id)
                }
                
                // Collect progress updates
                repository.downloadTalk(talk).collect { progress ->
                    _downloadProgress.value = progress
                    Log.d(TAG, "Download progress: ${(progress * 100).toInt()}%")
                }
                
                // Update download status on completion
                _downloadProgress.value = null
                _isDownloaded.value = true
                
                // Refresh downloaded talks list
                loadDownloadedTalks()
                
                Log.d(TAG, "Download completed for talk: ${talk.id}")
            } catch (e: Exception) {
                // Handle error cases
                Log.e(TAG, "Download error: ${e.message}", e)
                _downloadProgress.value = null
                
                // Show toast or other notification here
            }
        }
    }

    fun deleteTalk(talk: Talk) {
        viewModelScope.launch {
            repository.deleteTalk(talk.id)
            
            // Update the UI state to reflect that the talk is no longer downloaded
            _isDownloaded.value = false
            
            // Update the downloaded talks list
            loadDownloadedTalks()
            
            Log.d(TAG, "Deleted talk: ${talk.id}")
        }
    }
    
    fun refreshDownloads() {
        viewModelScope.launch {
            loadDownloadedTalks()
        }
    }
    
    fun loadTalk(talkId: String) {
        viewModelScope.launch {
            repository.getTalkById(talkId)?.let { talk ->
                // Check if there's an active download for this talk
                val isCurrentlyDownloading = _currentTalk.value?.id == talk.id && _downloadProgress.value != null
                
                // Only call setCurrentTalk if not currently downloading the same talk
                // This prevents download progress from being interrupted
                if (!isCurrentlyDownloading) {
                    setCurrentTalk(talk)
                } else {
                    // If we are already downloading this talk, we still want to update any other properties 
                    // but preserve the download state
                    _currentTalk.value = talk
                }
            }
        }
    }
    
    private fun loadFavorites() {
        viewModelScope.launch {
            _favoriteTalks.value = repository.getFavoriteTalks()
        }
    }
    
    fun toggleFavorite(talk: Talk) {
        viewModelScope.launch {
            val updatedTalk = repository.toggleFavorite(talk)
            
            // Update the current talk if it's the one being favorited/unfavorited
            if (_currentTalk.value?.id == talk.id) {
                _currentTalk.value = updatedTalk
            }
            
            // Update search results if any contain the favorited/unfavorited talk
            val currentSearchState = _searchState.value
            if (currentSearchState is SearchState.Success) {
                val updatedResults = currentSearchState.response.results.map { 
                    if (it.id == talk.id) updatedTalk else it 
                }
                val updatedResponse = SearchResponse(
                    total = currentSearchState.response.total,
                    results = updatedResults
                )
                _searchState.value = SearchState.Success(updatedResponse)
            }
            
            // Update downloaded talks list if it contains the favorited/unfavorited talk
            _downloadedTalks.value = _downloadedTalks.value.map { 
                if (it.id == talk.id) updatedTalk else it 
            }
            
            // Update recent plays if they contain the favorited/unfavorited talk
            _recentPlays.value = _recentPlays.value.map { 
                if (it.id == talk.id) updatedTalk else it 
            }
            
            // Refresh the favorites list
            loadFavorites()
        }
    }
    
    override fun onCleared() {
        stopAudioService()
        player?.release()
        player = null
        super.onCleared()
    }
    
    private fun stopAudioService() {
        // Stop the media service when the ViewModel is cleared
        try {
            val context = getApplication<Application>().applicationContext
            val intent = Intent(context, Class.forName("space.coljac.FreeAudio.playback.AudioService"))
            context.stopService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service", e)
        }
    }

    private fun initializePlayer() {
        if (player == null) {
            player = ExoPlayer.Builder(getApplication()).build().apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        updatePlaybackState()
                    }
                    
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        updatePlaybackState()
                    }
                    
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        updatePlaybackState()
                    }
                })
            }
        }
    }
    
    /**
     * Method to synchronize playback state with the service
     * This allows Bluetooth and lock screen controls to correctly sync with the UI
     */
    fun syncPlaybackState(isPlaying: Boolean) {
        Log.d(TAG, "Syncing external playback state change: isPlaying=$isPlaying")
        
        // Update local player state if there's a mismatch
        player?.let { exoPlayer ->
            Log.d(TAG, "Current local player state: isPlaying=${exoPlayer.isPlaying}, " +
                      "playWhenReady=${exoPlayer.playWhenReady}, " +
                      "playbackState=${playbackStateToString(exoPlayer.playbackState)}")
            
            if (exoPlayer.isPlaying != isPlaying) {
                try {
                    if (isPlaying) {
                        // Use multiple methods to ensure playback starts
                        Log.d(TAG, "Starting playback in local player")
                        
                        // Make sure we're prepared
                        if (exoPlayer.playbackState == Player.STATE_IDLE) {
                            exoPlayer.prepare()
                        }
                        
                        // Method 1: Use play()
                        exoPlayer.play()
                        
                        // Method 2: Set playWhenReady directly
                        exoPlayer.playWhenReady = true
                    } else {
                        // Use multiple methods to ensure playback stops
                        Log.d(TAG, "Pausing playback in local player")
                        
                        // Method 1: Use pause()
                        exoPlayer.pause()
                        
                        // Method 2: Set playWhenReady directly
                        exoPlayer.playWhenReady = false
                    }
                    
                    // Force update UI state immediately
                    _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
                    
                    // Re-check after a delay to ensure the state actually changed
                    Handler(Looper.getMainLooper()).postDelayed({
                        Log.d(TAG, "Verifying player state change: local player isPlaying=${exoPlayer.isPlaying}")
                        _playbackState.value = _playbackState.value.copy(isPlaying = exoPlayer.isPlaying)
                    }, 200)
                    
                    Log.d(TAG, "Local player state updated to match service: isPlaying=$isPlaying")
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing playback state", e)
                }
            }
        }
    }
    
    // Helper to convert player state to string for logging
    private fun playbackStateToString(state: Int): String {
        return when (state) {
            Player.STATE_IDLE -> "STATE_IDLE"
            Player.STATE_BUFFERING -> "STATE_BUFFERING"
            Player.STATE_READY -> "STATE_READY"
            Player.STATE_ENDED -> "STATE_ENDED"
            else -> "UNKNOWN"
        }
    }
    
    private fun updatePlaybackState() {
        player?.let { exoPlayer ->
            // Get the current track based on the media item index
            val currentTrack = _currentTalk.value?.let { talk ->
                val index = exoPlayer.currentMediaItemIndex
                if (index >= 0 && index < talk.tracks.size) talk.tracks[index] else null
            }
            
            _playbackState.value = _playbackState.value.copy(
                isPlaying = exoPlayer.isPlaying,
                currentTrackIndex = exoPlayer.currentMediaItemIndex,
                position = exoPlayer.currentPosition,
                duration = exoPlayer.duration,
                currentTrack = currentTrack
            )
        }
    }
} 