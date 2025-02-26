package space.coljac.FreeAudio.viewmodel

import android.app.Application
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
        val duration: Long = 0
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
                val results = fbaService.search(query)
                Log.d(TAG, "Got ${results.total} results")
                _searchState.value = SearchState.Success(results)
            } catch (e: Exception) {
                Log.e(TAG, "Search error", e)
                _searchState.value = SearchState.Error(e.message ?: "Unknown error")
            }
        }
    }
    
    fun playTalk(talk: Talk) {
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
                MediaItem.Builder()
                    .setUri(uri)
                    .setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(talk.speaker)
                            .setAlbumTitle(talk.title)
                            .setArtworkUri(Uri.parse(talk.imageUrl))
                            .build()
                    )
                    .build()
            }
            setMediaItems(mediaItems)
            prepare()
            // Don't automatically play, just prepare the player
            // The user will need to press play to start playback
        }
        
        viewModelScope.launch {
            repository.addToRecentPlays(talk)
            _recentPlays.value = repository.getRecentPlays()
        }
    }
    
    fun setCurrentTalk(talk: Talk) {
        _currentTalk.value = talk
        viewModelScope.launch {
            _isDownloaded.value = repository.isDownloaded(talk.id)
        }
    }

    fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) {
                it.pause()
                _playbackState.value = _playbackState.value.copy(isPlaying = false)
            } else {
                it.play()
                _playbackState.value = _playbackState.value.copy(isPlaying = true)
            }
            // Update all aspects of playback state for consistency
            updatePlaybackState()
        }
    }

    fun seekForward() {
        player?.seekForward()
        updatePlaybackState()
    }

    fun seekBackward() {
        player?.seekBack()
        updatePlaybackState()
    }

    fun skipToNextTrack() {
        player?.seekToNextMediaItem()
        updatePlaybackState()
    }

    fun skipToPreviousTrack() {
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
        player?.release()
        player = null
        super.onCleared()
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
    
    private fun updatePlaybackState() {
        player?.let { exoPlayer ->
            _playbackState.value = _playbackState.value.copy(
                isPlaying = exoPlayer.isPlaying,
                currentTrackIndex = exoPlayer.currentMediaItemIndex,
                position = exoPlayer.currentPosition,
                duration = exoPlayer.duration
            )
        }
    }
} 