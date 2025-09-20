package space.coljac.FreeAudio.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import space.coljac.FreeAudio.data.SearchResponse
import space.coljac.FreeAudio.data.SearchState
import space.coljac.FreeAudio.data.Talk
import space.coljac.FreeAudio.data.Track
import space.coljac.FreeAudio.network.FBAService
import space.coljac.FreeAudio.data.TalkRepository
import android.net.Uri
import space.coljac.FreeAudio.playback.AudioService

private const val TAG = "AudioViewModel"

class AudioViewModel(application: Application) : AndroidViewModel(application) {
    private val fbaService = FBAService()
    private val repository = TalkRepository(application)
    private val _searchState = MutableStateFlow<SearchState>(SearchState.Empty)
    val searchState: StateFlow<SearchState> = _searchState
    
    private val _isUpdatingSearchResults = MutableStateFlow(false)
    val isUpdatingSearchResults: StateFlow<Boolean> = _isUpdatingSearchResults
    
    // Media3 controller bound to our MediaSessionService
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    // Tracks which talk ID we most recently enqueued into the controller
    private var queuedTalkId: String? = null
    
    private val _currentTalk = MutableStateFlow<Talk?>(null)
    val currentTalk: StateFlow<Talk?> = _currentTalk

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress

    private val _isDownloaded = MutableStateFlow(false)
    val isDownloaded: StateFlow<Boolean> = _isDownloaded
    
    // Track download errors to communicate to UI
    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError

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
        loadRecentPlays()
        initializeController()
    }
    
    private fun loadRecentPlays() {
        viewModelScope.launch {
            _recentPlays.value = repository.getRecentPlays()
            Log.d(TAG, "Loaded ${_recentPlays.value.size} recent plays on startup")
        }
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
        Log.d(TAG, "playTalk(talkId=${talk.id}, requestedIndex=$startTrackIndex, tracks=${talk.tracks.size})")
        setCurrentTalk(talk)
        val c = ensureControllerReady() ?: return

        // If we're already playing the same talk with the same queue size, avoid rebuilding
        val sameTalk = _currentTalk.value?.id == talk.id
        if (sameTalk && c.mediaItemCount == talk.tracks.size) {
            val desiredIndex = if (talk.tracks.isNotEmpty()) startTrackIndex.coerceIn(0, talk.tracks.size - 1) else 0
            if (c.currentMediaItemIndex == desiredIndex) {
                Log.d(TAG, "Same talk and index; resuming without rebuilding queue")
                c.play()
                updatePlaybackState()
                return
            }
        }

        val mediaItems = talk.tracks.map { track ->
            val localPath = repository.getLocalTalkPath(talk.id, track.number)
            val uri = if (localPath != null) {
                Uri.parse("file://$localPath")
            } else {
                Uri.parse(track.path)
            }
            Log.d(TAG, "Queue item: #${track.number} '${track.title}' uri=$uri")

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
                        .build()
                )
                .build()
        }

        val validTrackIndex = if (talk.tracks.isNotEmpty()) startTrackIndex.coerceIn(0, talk.tracks.size - 1) else 0
        Log.d(TAG, "Setting media items (count=${mediaItems.size}) startIndex=$validTrackIndex")
        c.setMediaItems(mediaItems, validTrackIndex, 0)
        c.prepare()
        c.play()
        Log.d(TAG, "Called play(); controllerIndex=${c.currentMediaItemIndex}")

        _playbackState.value = _playbackState.value.copy(
            currentTrackIndex = validTrackIndex,
            currentTrack = talk.tracks.getOrNull(validTrackIndex),
            isPlaying = true
        )
        queuedTalkId = talk.id

        viewModelScope.launch {
            repository.addToRecentPlays(talk)
            loadRecentPlays()
        }
    }

    // Called from Talk detail main Play/Pause button. If the displayed talk
    // is not the one currently queued, begin playback of this talk from start.
    // Otherwise, just toggle play/pause.
    fun playOrToggleCurrentTalk() {
        val talk = _currentTalk.value ?: return
        val c = ensureControllerReady() ?: return
        val isSameQueuedTalk = queuedTalkId == talk.id && c.mediaItemCount > 0
        Log.d(TAG, "playOrToggleCurrentTalk sameQueued=$isSameQueuedTalk queuedTalkId=$queuedTalkId currentTalkId=${talk.id}")
        if (isSameQueuedTalk) {
            togglePlayPause()
        } else {
            playTalk(talk, 0)
        }
    }
    
    private fun initializeController() {
        if (controllerFuture != null) return
        val context = getApplication<Application>()
        val sessionToken = SessionToken(context, android.content.ComponentName(context, AudioService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                controller?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
                        if (isPlaying) startProgressUpdates() else stopProgressUpdates()
                        Log.d(TAG, "onIsPlayingChanged=$isPlaying index=${controller?.currentMediaItemIndex}")
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        Log.d(TAG, "onMediaItemTransition reason=$reason index=${controller?.currentMediaItemIndex} title=${mediaItem?.mediaMetadata?.title}")
                        updatePlaybackState()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        Log.d(TAG, "onPlaybackStateChanged state=$playbackState index=${controller?.currentMediaItemIndex}")
                        updatePlaybackState()
                    }
                })
                updatePlaybackState()
            } catch (e: Exception) {
                Log.e(TAG, "Error creating MediaController", e)
            }
        }, MoreExecutors.directExecutor())
    }
    
    fun setCurrentTalk(talk: Talk) {
        _currentTalk.value = talk
        viewModelScope.launch {
            _isDownloaded.value = repository.isDownloaded(talk.id)
        }
    }

    fun togglePlayPause() {
        val c = ensureControllerReady() ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            c.play()
        }
        updatePlaybackState()
    }
    
    fun playTrack(trackIndex: Int) {
        val talk = _currentTalk.value ?: return
        val c = ensureControllerReady() ?: return
        if (talk.tracks.isEmpty()) return
        // Always (re)build the playlist for this talk and start at the selected index.
        // This avoids cases where an identical item count from a previous queue causes
        // us to seek within the wrong playlist or default to the first item.
        playTalk(talk, trackIndex)
    }

    fun seekForward() {
        val c = ensureControllerReady() ?: return
        c.seekTo(c.currentPosition + 10_000)
        updatePlaybackState()
    }

    fun seekBackward() {
        val c = ensureControllerReady() ?: return
        val newPosition = (c.currentPosition - 10_000).coerceAtLeast(0)
        c.seekTo(newPosition)
        updatePlaybackState()
    }

    fun seekTo(positionMs: Long) {
        val c = ensureControllerReady() ?: return
        c.seekTo(positionMs)
        updatePlaybackState()
    }

    fun skipToNextTrack() {
        val c = ensureControllerReady() ?: return
        c.seekToNextMediaItem()
        updatePlaybackState()
    }

    fun skipToPreviousTrack() {
        val c = ensureControllerReady() ?: return
        c.seekToPreviousMediaItem()
        updatePlaybackState()
    }

    fun downloadTalk(talk: Talk) {
        // Don't allow downloading if already downloading
        if (_downloadProgress.value != null) return

        viewModelScope.launch {
            try {
                // Reset any previous download errors
                _downloadError.value = null
                
                // Set initial progress to indicate download has started
                _downloadProgress.value = 0f
                
                // Show "Downloading..." immediately
                Log.d(TAG, "Starting download for talk: ${talk.id}")
                
                // Make sure the current talk is set without triggering playback
                if (_currentTalk.value?.id != talk.id) {
                    _currentTalk.value = talk
                    _isDownloaded.value = repository.isDownloaded(talk.id)
                }
                
                try {
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
                    // Handle download errors gracefully
                    Log.e(TAG, "Download error: ${e.message}", e)
                    _downloadProgress.value = null
                    
                    // Set error message for UI to display
                    val errorMessage = when {
                        e.message?.contains("connection abort") == true -> 
                            "Connection error. Please check your internet connection and try again."
                        e.message?.contains("timeout") == true ->
                            "Download timed out. Please try again later."
                        e.message?.contains("404") == true ->
                            "Talk not found on server. Please try a different talk."
                        e.message?.contains("HTTP error") == true ->
                            "Server error. Please try again later."
                        else -> "Download failed: ${e.message ?: "Unknown error"}"
                    }
                    
                    _downloadError.value = errorMessage
                    Log.e(TAG, "Download error message: $errorMessage")
                }
            } catch (e: Exception) {
                // Catch any unexpected errors in the flow setup
                Log.e(TAG, "Unexpected error setting up download: ${e.message}", e)
                _downloadProgress.value = null
                _downloadError.value = "Failed to start download: ${e.message ?: "Unknown error"}"
            }
        }
    }
    
    // Method to clear download errors (to be called after user dismisses error message)
    fun clearDownloadError() {
        _downloadError.value = null
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
    
    fun refreshRecentPlays() {
        viewModelScope.launch {
            loadRecentPlays()
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
        controller?.release()
        controller = null
        controllerFuture = null
        super.onCleared()
    }
    
    private fun ensureControllerReady(): MediaController? {
        if (controller != null) return controller
        initializeController()
        return controller
    }

    private var progressJob: Job? = null
    private fun startProgressUpdates() {
        if (progressJob != null) return
        progressJob = viewModelScope.launch {
            while (controller?.isPlaying == true) {
                updatePlaybackState()
                delay(500)
            }
            progressJob = null
        }
    }
    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }
    
    // Media3-only: no compat sync
    
    private fun updatePlaybackState() {
        controller?.let { c ->
            val currentTrack = _currentTalk.value?.let { talk ->
                val index = c.currentMediaItemIndex
                if (index >= 0 && index < talk.tracks.size) talk.tracks[index] else null
            }
            _playbackState.value = _playbackState.value.copy(
                isPlaying = c.isPlaying,
                currentTrackIndex = c.currentMediaItemIndex,
                position = c.currentPosition,
                duration = c.duration,
                currentTrack = currentTrack
            )
            Log.d(
                TAG,
                "updatePlaybackState isPlaying=${c.isPlaying} index=${c.currentMediaItemIndex} position=${c.currentPosition} duration=${c.duration} title='${currentTrack?.title}'"
            )
        }
    }
}
