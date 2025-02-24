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

    data class PlaybackState(
        val isPlaying: Boolean = false,
        val currentTrackIndex: Int = 0,
        val position: Long = 0,
        val duration: Long = 0
    )

    init {
        loadDownloadedTalks()
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
            play()
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
            } else {
                it.play()
            }
            _playbackState.value = _playbackState.value.copy(isPlaying = it.isPlaying)
        }
    }

    fun seekForward() {
        player?.seekForward()
    }

    fun seekBackward() {
        player?.seekBack()
    }

    fun skipToNextTrack() {
        player?.seekToNextMediaItem()
    }

    fun skipToPreviousTrack() {
        player?.seekToPreviousMediaItem()
    }

    fun downloadTalk(talk: Talk) {
        viewModelScope.launch {
            try {
                repository.downloadTalk(talk).collect { progress ->
                    _downloadProgress.value = progress
                }
                _downloadProgress.value = null
                _isDownloaded.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Download error", e)
                _downloadProgress.value = null
            }
        }
    }

    fun deleteTalk(talk: Talk) {
        viewModelScope.launch {
            repository.deleteTalk(talk.id)
            loadDownloadedTalks()
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
                setCurrentTalk(talk)
            }
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
                        _playbackState.value = _playbackState.value.copy(
                            currentTrackIndex = currentMediaItemIndex,
                            position = currentPosition,
                            duration = duration
                        )
                    }
                })
            }
        }
    }
} 