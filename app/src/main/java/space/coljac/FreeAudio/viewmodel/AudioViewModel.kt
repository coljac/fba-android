package space.coljac.FreeAudio.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
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
import space.coljac.FreeAudio.data.PlaybackProgressRepository
import space.coljac.FreeAudio.data.PlaybackProgress
import android.net.Uri
import space.coljac.FreeAudio.playback.AudioService

private const val TAG = "AudioViewModel"

class AudioViewModel(application: Application) : AndroidViewModel(application) {
    private val fbaService = FBAService()
    private val repository = TalkRepository(application)
    private val progressRepository = PlaybackProgressRepository(application)
    private val _searchState = MutableStateFlow<SearchState>(SearchState.Empty)
    val searchState: StateFlow<SearchState> = _searchState

    private val _isUpdatingSearchResults = MutableStateFlow(false)
    val isUpdatingSearchResults: StateFlow<Boolean> = _isUpdatingSearchResults

    private val _isLoadingMoreResults = MutableStateFlow(false)
    val isLoadingMoreResults: StateFlow<Boolean> = _isLoadingMoreResults

    private val _selectedSpeaker = MutableStateFlow<String?>(null)
    val selectedSpeaker: StateFlow<String?> = _selectedSpeaker

    private var currentSearchQuery: String = ""
    private var currentSpeakerFilter: String? = null
    private val searchPageSize = 10

    // Media3 controller bound to our MediaLibraryService
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

    // Playback error state for user feedback
    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError

    // Playback speed
    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed

    // Sleep timer
    private val _sleepTimerRemainingMs = MutableStateFlow(0L)
    val sleepTimerRemainingMs: StateFlow<Long> = _sleepTimerRemainingMs
    private var sleepTimerJob: Job? = null
    private var sleepTimerEndOfTrack = false

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
            val recent = repository.getRecentPlays()
            _recentPlays.value = recent
            Log.d(TAG, "Loaded ${recent.size} recent plays on startup")

            // Auto-load the most recent talk into the UI (but don't play yet)
            if (_currentTalk.value == null && recent.isNotEmpty()) {
                val lastTalk = recent.first()
                _currentTalk.value = lastTalk
                _isDownloaded.value = repository.isDownloaded(lastTalk.id)

                // Restore visual state from progress
                val savedProgress = progressRepository.getProgress(lastTalk.id)
                if (savedProgress != null) {
                    val track = lastTalk.tracks.getOrNull(savedProgress.trackIndex)
                    val duration = (track?.durationSeconds ?: 0) * 1000L

                    _playbackState.value = _playbackState.value.copy(
                        position = savedProgress.positionMs,
                        currentTrackIndex = savedProgress.trackIndex,
                        currentTrack = track,
                        duration = duration
                    )
                }
            }
        }
    }

    private fun loadDownloadedTalks() {
        viewModelScope.launch {
            _downloadedTalks.value = repository.getDownloadedTalks()
        }
    }

    fun search(query: String) {
        // New query → drop any existing speaker filter, since the available
        // speakers will be different for the new result set.
        if (query != currentSearchQuery) {
            currentSpeakerFilter = null
            _selectedSpeaker.value = null
        }
        runSearch(query)
    }

    fun setSpeakerFilter(speaker: String?) {
        val normalized = speaker?.takeIf { it.isNotBlank() }
        if (currentSpeakerFilter == normalized) return
        currentSpeakerFilter = normalized
        _selectedSpeaker.value = normalized
        if (currentSearchQuery.isNotEmpty()) {
            runSearch(currentSearchQuery)
        }
    }

    private fun runSearch(query: String) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Searching for: $query (speaker=$currentSpeakerFilter)")
                currentSearchQuery = query
                val speakerAtStart = currentSpeakerFilter
                _isLoadingMoreResults.value = false
                _searchState.value = SearchState.Loading
                val searchResults = fbaService.search(query, 0, searchPageSize, speakerAtStart)
                Log.d(TAG, "Got ${searchResults.total} results (page 1, ${searchResults.results.size} shown)")

                // First display the initial results
                _searchState.value = SearchState.Success(searchResults)

                // Then update track information in the background
                updateTrackDetailsForTalks(searchResults.results, query, speakerAtStart)
            } catch (e: Exception) {
                Log.e(TAG, "Search error", e)
                _searchState.value = SearchState.Error(e.message ?: "Unknown error")
                _isUpdatingSearchResults.value = false
            }
        }
    }

    fun loadMoreSearchResults() {
        val currentState = _searchState.value
        if (currentState !is SearchState.Success) return
        if (_isLoadingMoreResults.value) return
        if (currentSearchQuery.isEmpty()) return
        val alreadyLoaded = currentState.response.results.size
        if (alreadyLoaded >= currentState.response.total) return

        val queryAtStart = currentSearchQuery
        val speakerAtStart = currentSpeakerFilter
        val offset = alreadyLoaded

        viewModelScope.launch {
            _isLoadingMoreResults.value = true
            try {
                Log.d(TAG, "Loading more from offset $offset for '$queryAtStart' speaker=$speakerAtStart")
                val nextPage = fbaService.search(queryAtStart, offset, searchPageSize, speakerAtStart)

                // Bail if the active query/filter changed while we were waiting
                if (queryAtStart != currentSearchQuery || speakerAtStart != currentSpeakerFilter) {
                    Log.d(TAG, "Query/filter changed while loading more; discarding stale page")
                    return@launch
                }

                val latestState = _searchState.value
                if (latestState is SearchState.Success) {
                    // Avoid duplicates if a page somehow overlaps existing ids
                    val existingIds = latestState.response.results.map { it.id }.toHashSet()
                    val newOnly = nextPage.results.filter { it.id !in existingIds }
                    val combined = latestState.response.results + newOnly

                    // If the server returned no new items, treat this as "no more
                    // available" and cap the total so the UI stops asking for more
                    // (otherwise we'd loop forever when total is wrong).
                    val effectiveTotal = if (newOnly.isEmpty()) combined.size else nextPage.total

                    _searchState.value = SearchState.Success(
                        latestState.response.copy(
                            total = effectiveTotal,
                            results = combined,
                            availableSpeakers = nextPage.availableSpeakers.ifEmpty {
                                latestState.response.availableSpeakers
                            }
                        )
                    )
                    Log.d(TAG, "Appended ${newOnly.size} new results (total shown: ${combined.size}/$effectiveTotal)")

                    // Hide the load-more spinner before kicking off the slower detail fetch
                    _isLoadingMoreResults.value = false

                    if (newOnly.isNotEmpty()) {
                        updateTrackDetailsForTalks(newOnly, queryAtStart, speakerAtStart)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Load more error", e)
            } finally {
                _isLoadingMoreResults.value = false
            }
        }
    }

    /**
     * Fetch detailed track info for the given talks and merge results into the
     * current search state by id. Aborts if the active query/filter changes mid-flight.
     */
    private suspend fun updateTrackDetailsForTalks(
        talks: List<Talk>,
        queryAtStart: String,
        speakerAtStart: String?
    ) {
        if (talks.isEmpty()) return
        _isUpdatingSearchResults.value = true
        try {
            val updatedById = mutableMapOf<String, Talk>()
            for (talk in talks) {
                if (queryAtStart != currentSearchQuery || speakerAtStart != currentSpeakerFilter) return
                try {
                    val detailedTalk = repository.getTalkById(talk.id)
                    if (detailedTalk != null && detailedTalk.tracks.isNotEmpty()) {
                        Log.d(TAG, "Updated track count for ${talk.id}: was ${talk.tracks.size}, now ${detailedTalk.tracks.size}")
                        updatedById[talk.id] = detailedTalk
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get detailed info for talk ${talk.id}", e)
                }
            }
            if (updatedById.isEmpty()) return
            if (queryAtStart != currentSearchQuery || speakerAtStart != currentSpeakerFilter) return

            val curState = _searchState.value
            if (curState is SearchState.Success) {
                val merged = curState.response.results.map { updatedById[it.id] ?: it }
                _searchState.value = SearchState.Success(curState.response.copy(results = merged))
            }
        } finally {
            _isUpdatingSearchResults.value = false
        }
    }

    fun playTalk(talk: Talk, startTrackIndex: Int = 0, startPositionMs: Long = 0, checkSavedProgress: Boolean = true) {
        Log.d(TAG, "playTalk(talkId=${talk.id}, requestedIndex=$startTrackIndex, tracks=${talk.tracks.size}, checkSavedProgress=$checkSavedProgress)")

        // If requested and no explicit position is provided, check for saved progress
        if (checkSavedProgress && startPositionMs == 0L && startTrackIndex == 0) {
            viewModelScope.launch {
                val savedProgress = progressRepository.getProgress(talk.id)
                if (savedProgress != null) {
                    Log.d(TAG, "Found saved progress for talk ${talk.id}: track ${savedProgress.trackIndex}, position ${savedProgress.positionMs}ms")
                    playTalk(talk, savedProgress.trackIndex, savedProgress.positionMs, checkSavedProgress = false)
                    return@launch
                } else {
                    Log.d(TAG, "No saved progress found for talk ${talk.id}")
                    playTalk(talk, startTrackIndex, startPositionMs, checkSavedProgress = false)
                }
            }
            return
        }

        setCurrentTalk(talk)
        val c = ensureControllerReady() ?: return

        // If we're already playing the same talk with the same queue size, avoid rebuilding
        val sameTalk = queuedTalkId == talk.id
        if (sameTalk && c.mediaItemCount == talk.tracks.size) {
            val desiredIndex = if (talk.tracks.isNotEmpty()) startTrackIndex.coerceIn(0, talk.tracks.size - 1) else 0
            if (c.currentMediaItemIndex == desiredIndex) {
                Log.d(TAG, "Same talk and index; resuming without rebuilding queue")
                if (startPositionMs > 0) {
                    c.seekTo(startPositionMs)
                }
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
        Log.d(TAG, "Setting media items (count=${mediaItems.size}) startIndex=$validTrackIndex startPositionMs=$startPositionMs")
        c.setMediaItems(mediaItems, validTrackIndex, startPositionMs)
        c.prepare()
        c.play()

        // Reapply playback speed if non-default
        if (_playbackSpeed.value != 1.0f) {
            c.playbackParameters = PlaybackParameters(_playbackSpeed.value)
        }

        Log.d(TAG, "Called play(); controllerIndex=${c.currentMediaItemIndex}")

        _playbackState.value = _playbackState.value.copy(
            currentTrackIndex = validTrackIndex,
            currentTrack = talk.tracks.getOrNull(validTrackIndex),
            isPlaying = true
        )
        queuedTalkId = talk.id
        lastSavedTrackIndex = validTrackIndex

        viewModelScope.launch {
            repository.addToRecentPlays(talk)
            loadRecentPlays()
        }
    }

    // Resume talk from saved progress
    fun resumeTalkFromSavedProgress(talk: Talk) {
        playTalk(talk, checkSavedProgress = true)
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
            resumeTalkFromSavedProgress(talk)
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
                        if (isPlaying) {
                            startProgressUpdates()
                        } else {
                            stopProgressUpdates()
                            // Save progress when playback is paused
                            saveCurrentProgress()
                        }
                        Log.d(TAG, "onIsPlayingChanged=$isPlaying index=${controller?.currentMediaItemIndex}")
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        Log.d(TAG, "onMediaItemTransition reason=$reason index=${controller?.currentMediaItemIndex} title=${mediaItem?.mediaMetadata?.title}")
                        // Update to the new track index
                        lastSavedTrackIndex = controller?.currentMediaItemIndex ?: -1
                        updatePlaybackState()

                        // Check for sleep timer "end of track" mode
                        if (sleepTimerEndOfTrack) {
                            controller?.pause()
                            cancelSleepTimer()
                        }
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        // Save progress for the completed track using the actual old position
                        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                            saveProgressForTrack(oldPosition.mediaItemIndex, oldPosition.positionMs)
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        Log.d(TAG, "onPlaybackStateChanged state=$playbackState index=${controller?.currentMediaItemIndex}")
                        updatePlaybackState()

                        // Detect talk completion
                        if (playbackState == Player.STATE_ENDED) {
                            Log.d(TAG, "Playback ended - talk completed")
                            saveCurrentProgress()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "Player error: ${error.errorCodeName} - ${error.message}", error)
                        queuedTalkId = null
                        val errorMessage = when (error.errorCode) {
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                                "Network error. Please check your connection."
                            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                                "Could not load audio from server."
                            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                                "Audio file not found."
                            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                            PlaybackException.ERROR_CODE_DECODING_FAILED ->
                                "Unable to play this audio format."
                            else ->
                                "Playback error: ${error.message ?: error.errorCodeName}"
                        }
                        _playbackError.value = errorMessage
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
        // Don't check saved progress when user explicitly selects a track
        playTalk(talk, trackIndex, checkSavedProgress = false)
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
                    Log.e(TAG, "Download error: ${e.message}", e)
                    _downloadProgress.value = null

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
                Log.e(TAG, "Unexpected error setting up download: ${e.message}", e)
                _downloadProgress.value = null
                _downloadError.value = "Failed to start download: ${e.message ?: "Unknown error"}"
            }
        }
    }

    fun clearDownloadError() {
        _downloadError.value = null
    }

    fun clearPlaybackError() {
        _playbackError.value = null
    }

    fun deleteTalk(talk: Talk) {
        viewModelScope.launch {
            repository.deleteTalk(talk.id)
            _isDownloaded.value = false
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
                val isCurrentlyDownloading = _currentTalk.value?.id == talk.id && _downloadProgress.value != null
                if (!isCurrentlyDownloading) {
                    setCurrentTalk(talk)
                } else {
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

            if (_currentTalk.value?.id == talk.id) {
                _currentTalk.value = updatedTalk
            }

            val currentSearchState = _searchState.value
            if (currentSearchState is SearchState.Success) {
                val updatedResults = currentSearchState.response.results.map {
                    if (it.id == talk.id) updatedTalk else it
                }
                _searchState.value = SearchState.Success(
                    currentSearchState.response.copy(results = updatedResults)
                )
            }

            _downloadedTalks.value = _downloadedTalks.value.map {
                if (it.id == talk.id) updatedTalk else it
            }

            _recentPlays.value = _recentPlays.value.map {
                if (it.id == talk.id) updatedTalk else it
            }

            loadFavorites()
        }
    }

    // Playback speed control
    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        controller?.playbackParameters = PlaybackParameters(speed)
    }

    // Sleep timer - set countdown in minutes
    fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        sleepTimerEndOfTrack = false

        if (minutes <= 0) return

        val endTime = System.currentTimeMillis() + minutes * 60_000L
        sleepTimerJob = viewModelScope.launch {
            while (true) {
                val remaining = endTime - System.currentTimeMillis()
                if (remaining <= 0) {
                    _sleepTimerRemainingMs.value = 0L
                    controller?.pause()
                    sleepTimerJob = null
                    break
                }
                _sleepTimerRemainingMs.value = remaining
                delay(1000)
            }
        }
    }

    // Sleep timer - pause at end of current track
    fun setSleepTimerEndOfTrack() {
        cancelSleepTimer()
        sleepTimerEndOfTrack = true
        _sleepTimerRemainingMs.value = -1L // -1 signals "end of track" mode
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerEndOfTrack = false
        _sleepTimerRemainingMs.value = 0L
    }

    override fun onCleared() {
        // Save progress before clearing
        saveCurrentProgress()
        cancelSleepTimer()

        controller?.release()
        controller = null
        controllerFuture = null
        super.onCleared()
    }

    private fun ensureControllerReady(): MediaController? {
        if (controller != null) return controller
        // Controller is being initialised asynchronously (from init block).
        // If not ready yet, the caller should handle the null gracefully.
        Log.w(TAG, "MediaController not ready yet")
        return null
    }

    private var progressJob: Job? = null
    private var lastProgressSaveTime = 0L
    private var lastSavedTrackIndex = -1

    private fun startProgressUpdates() {
        if (progressJob != null) return
        progressJob = viewModelScope.launch {
            var updateCount = 0
            while (controller?.isPlaying == true) {
                updatePlaybackState()
                updateCount++

                // Save progress every 10 seconds (20 updates at 500ms intervals)
                if (updateCount >= 20) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastProgressSaveTime > 10000) {
                        saveCurrentProgress()
                        lastProgressSaveTime = currentTime
                    }
                    updateCount = 0
                }

                delay(500)
            }
            // Save progress when playback stops
            saveCurrentProgress()
            progressJob = null
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun updatePlaybackState() {
        controller?.let { c ->
            val talk = _currentTalk.value
            val index = c.currentMediaItemIndex
            val currentTrack = talk?.let {
                if (index >= 0 && index < it.tracks.size) it.tracks[index] else null
            }

            // If ExoPlayer knows the real duration and the track's stored duration is
            // missing/unknown, backfill it from the media itself.
            val playerDurationMs = c.duration
            if (talk != null && playerDurationMs > 0 && index >= 0 && index < talk.tracks.size) {
                val track = talk.tracks[index]
                if (track.duration.isEmpty() || track.durationSeconds <= 0) {
                    val secs = (playerDurationMs / 1000).toInt()
                    val mins = secs / 60
                    val rem = secs % 60
                    val formatted = "$mins:${rem.toString().padStart(2, '0')}"
                    val updatedTrack = track.copy(duration = formatted, durationSeconds = secs)
                    val updatedTracks = talk.tracks.toMutableList()
                    updatedTracks[index] = updatedTrack
                    _currentTalk.value = talk.copy(tracks = updatedTracks)
                }
            }

            _playbackState.value = _playbackState.value.copy(
                isPlaying = c.isPlaying,
                currentTrackIndex = index,
                position = c.currentPosition,
                duration = playerDurationMs,
                currentTrack = currentTrack
            )
        }
    }

    private fun saveCurrentProgress() {
        val talk = _currentTalk.value ?: return
        val c = controller ?: return

        val position = c.currentPosition
        if (position > 1000) {
            val progress = PlaybackProgress(
                talkId = talk.id,
                trackIndex = c.currentMediaItemIndex,
                positionMs = position
            )

            viewModelScope.launch {
                progressRepository.saveProgress(progress)
            }
        }
    }

    /**
     * Save progress for a specific track at a specific position.
     * Used during track transitions via onPositionDiscontinuity which provides
     * the actual old position (unlike onMediaItemTransition where the controller
     * has already moved to the new track).
     */
    private fun saveProgressForTrack(trackIndex: Int, positionMs: Long) {
        val talk = _currentTalk.value ?: return

        if (positionMs > 1000) {
            val progress = PlaybackProgress(
                talkId = talk.id,
                trackIndex = trackIndex,
                positionMs = positionMs
            )

            viewModelScope.launch {
                progressRepository.saveProgress(progress)
            }
        }
    }

    fun pauseAndSaveProgress() {
        saveCurrentProgress()
        togglePlayPause()
    }
}
