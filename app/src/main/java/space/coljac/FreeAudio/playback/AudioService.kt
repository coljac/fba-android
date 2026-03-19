package space.coljac.FreeAudio.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import space.coljac.FreeAudio.MainActivity
import space.coljac.FreeAudio.data.Talk
import space.coljac.FreeAudio.data.TalkRepository
import space.coljac.FreeAudio.network.FBAService

private const val TAG = "AudioService"
private const val NOTIFICATION_CHANNEL_ID = "fba_playback_channel"
private const val NOTIFICATION_CHANNEL_NAME = "Audio Playback"

private const val MEDIA_ID_ROOT = "[ROOT]"
private const val MEDIA_ID_RECENT = "[RECENT]"
private const val MEDIA_ID_FAVOURITES = "[FAVOURITES]"
private const val MEDIA_ID_DOWNLOADS = "[DOWNLOADS]"
private const val MEDIA_ID_TALK_PREFIX = "talk:"

@UnstableApi
class AudioService : MediaLibraryService() {
    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private lateinit var talkRepository: TalkRepository
    private lateinit var fbaService: FBAService
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Cache search results for onGetSearchResult
    @Volatile
    private var lastSearchResults: List<Talk> = emptyList()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AudioService onCreate")

        createNotificationChannel()
        talkRepository = TalkRepository(this)
        fbaService = FBAService()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "Service onIsPlayingChanged=$isPlaying index=${player.currentMediaItemIndex}")
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                Log.d(TAG, "Service onMediaItemTransition reason=$reason index=${player.currentMediaItemIndex} title=${mediaItem?.mediaMetadata?.title}")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(TAG, "Service onPlaybackStateChanged state=$playbackState index=${player.currentMediaItemIndex}")
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Player error: ${error.errorCodeName} - ${error.message}", error)
            }
        })

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, LibrarySessionCallback())
            .setSessionActivity(sessionActivity)
            .setId("FreeAudio_media_session")
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for audio playback"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaLibrarySession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        // If playback is active, keep the service alive
    }

    override fun onDestroy() {
        Log.d(TAG, "AudioService onDestroy")
        serviceScope.cancel()
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        super.onDestroy()
    }

    private fun talkToMediaItem(talk: Talk): MediaItem {
        return MediaItem.Builder()
            .setMediaId("$MEDIA_ID_TALK_PREFIX${talk.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(talk.title)
                    .setArtist(talk.speaker)
                    .setArtworkUri(Uri.parse(talk.imageUrl))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .build()
            )
            .build()
    }

    private fun buildFolderItem(mediaId: String, title: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()
    }

    private suspend fun resolveMediaItem(item: MediaItem): List<MediaItem> {
        val mediaId = item.mediaId
        if (mediaId.startsWith(MEDIA_ID_TALK_PREFIX)) {
            val talkId = mediaId.removePrefix(MEDIA_ID_TALK_PREFIX)
            val talk = talkRepository.getTalkById(talkId) ?: return emptyList()
            return talk.tracks.map { track ->
                val localPath = talkRepository.getLocalTalkPath(talk.id, track.number)
                val uri = if (localPath != null) {
                    Uri.parse("file://$localPath")
                } else {
                    Uri.parse(track.path)
                }
                MediaItem.Builder()
                    .setMediaId("track:${talk.id}:${track.number}")
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
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
        }
        // If not a browse item, return as-is (might already be playable)
        return listOf(item)
    }

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId(MEDIA_ID_ROOT)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .setTitle("Free Buddhist Audio")
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                try {
                    val children: List<MediaItem> = when (parentId) {
                        MEDIA_ID_ROOT -> listOf(
                            buildFolderItem(MEDIA_ID_RECENT, "Recent Plays"),
                            buildFolderItem(MEDIA_ID_FAVOURITES, "Favourites"),
                            buildFolderItem(MEDIA_ID_DOWNLOADS, "Downloads")
                        )
                        MEDIA_ID_RECENT -> {
                            talkRepository.getRecentPlays().map { talkToMediaItem(it) }
                        }
                        MEDIA_ID_FAVOURITES -> {
                            talkRepository.getFavoriteTalksFromCache().map { talkToMediaItem(it) }
                        }
                        MEDIA_ID_DOWNLOADS -> {
                            talkRepository.getDownloadedTalks().map { talkToMediaItem(it) }
                        }
                        else -> emptyList()
                    }
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting children for $parentId", e)
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                }
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (mediaId.startsWith(MEDIA_ID_TALK_PREFIX)) {
                val future = SettableFuture.create<LibraryResult<MediaItem>>()
                serviceScope.launch {
                    try {
                        val talkId = mediaId.removePrefix(MEDIA_ID_TALK_PREFIX)
                        val talk = talkRepository.getTalkById(talkId)
                        if (talk != null) {
                            future.set(LibraryResult.ofItem(talkToMediaItem(talk), null))
                        } else {
                            future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting item $mediaId", e)
                        future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                    }
                }
                return future
            }
            return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            val future = SettableFuture.create<LibraryResult<Void>>()
            serviceScope.launch {
                try {
                    val results = fbaService.search(query)
                    lastSearchResults = results.results
                    future.set(LibraryResult.ofVoid(params))
                    session.notifySearchResultChanged(browser, query, results.results.size, params)
                } catch (e: Exception) {
                    Log.e(TAG, "Search error for query: $query", e)
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                }
            }
            return future
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items = lastSearchResults.map { talkToMediaItem(it) }
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val future = SettableFuture.create<List<MediaItem>>()
            serviceScope.launch {
                try {
                    val playableItems = mediaItems.flatMap { resolveMediaItem(it) }
                    future.set(playableItems)
                } catch (e: Exception) {
                    Log.e(TAG, "Error resolving media items", e)
                    future.setException(e)
                }
            }
            return future
        }
    }
}
