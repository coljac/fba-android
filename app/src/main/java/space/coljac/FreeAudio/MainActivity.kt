package space.coljac.FreeAudio

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import space.coljac.FreeAudio.playback.AudioService
import space.coljac.FreeAudio.ui.components.SearchBar
import space.coljac.FreeAudio.ui.components.TalkItem
import space.coljac.FreeAudio.ui.theme.FreeAudioTheme
import space.coljac.FreeAudio.viewmodel.AudioViewModel
import space.coljac.FreeAudio.data.SearchState
import space.coljac.FreeAudio.navigation.AppNavigation

private const val TAG = "MainActivity"

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
class MainActivity : ComponentActivity() {
    private val viewModel: AudioViewModel by viewModels()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null
        
    // MediaBrowserServiceCompat connection
    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null
    
    // Playback state broadcast receiver
    private val playbackStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "ACTION_PLAYBACK_STATE_CHANGED") {
                val isPlaying = intent.getBooleanExtra("IS_PLAYING", false)
                Log.d(TAG, "Received broadcast: playback state changed to isPlaying=$isPlaying")
                viewModel.syncPlaybackState(isPlaying)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Initialize both media browser service connections
        initializeMediaBrowserConnection()
        initializeMediaBrowserCompatConnection()
        
        setContent {
            FreeAudioTheme {
                AppNavigation(viewModel = viewModel)
            }
        }
    }
    
    // MediaBrowserCompat connection callbacks
    private val connectionCallbacks = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            Log.d(TAG, "Connected to MediaBrowserService")
            
            mediaBrowser?.let { browser ->
                // Get the token for creating a MediaController
                val mediaId = browser.root
                
                // Create a MediaControllerCompat
                val token = browser.sessionToken
                try {
                    mediaController = MediaControllerCompat(this@MainActivity, token)
                    
                    // Save the controller for later use
                    MediaControllerCompat.setMediaController(this@MainActivity, mediaController)
                    
                    // Register a callback to stay in sync
                    mediaController?.registerCallback(controllerCallback)
                    
                    // Update UI based on current state
                    controllerCallback.onPlaybackStateChanged(mediaController?.playbackState)
                    
                    Log.d(TAG, "MediaControllerCompat created successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create MediaController", e)
                }
            }
        }
        
        override fun onConnectionSuspended() {
            Log.d(TAG, "Connection to MediaBrowserService suspended")
            // Disable transport controls
            mediaController?.unregisterCallback(controllerCallback)
            mediaController = null
            MediaControllerCompat.setMediaController(this@MainActivity, null)
        }
        
        override fun onConnectionFailed() {
            Log.e(TAG, "Connection to MediaBrowserService failed")
        }
    }
    
    // MediaControllerCompat callbacks
    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            Log.d(TAG, "Playback state changed: ${state?.state}")
            
            // Sync the state with our ViewModel
            val isPlaying = state?.state == PlaybackStateCompat.STATE_PLAYING
            viewModel.syncPlaybackState(isPlaying)
        }
    }
    
    private fun initializeMediaBrowserCompatConnection() {
        if (mediaBrowser == null) {
            // Connect to the MediaBrowserServiceCompat
            mediaBrowser = MediaBrowserCompat(
                this,
                ComponentName(this, AudioService::class.java),
                connectionCallbacks,
                null
            )
            
            try {
                mediaBrowser?.connect()
                Log.d(TAG, "Connecting to MediaBrowserServiceCompat...")
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to MediaBrowserServiceCompat", e)
            }
        }
    }
    
    @UnstableApi
    private fun initializeMediaBrowserConnection() {
        // Create a session token for the media service
        val sessionToken = SessionToken(
            this,
            ComponentName(this, AudioService::class.java)
        )
        
        // Create a media controller future
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        
        // Add a listener to handle when the controller is ready
        controllerFuture?.addListener({
            try {
                val mediaController = controllerFuture?.get()
                Log.d(TAG, "MediaController successfully created")
            } catch (e: Exception) {
                Log.e(TAG, "Error creating MediaController", e)
            }
        }, MoreExecutors.directExecutor())
    }
    
    override fun onStart() {
        super.onStart()
        // Re-establish both connections to the media browser services
        if (controllerFuture == null) {
            initializeMediaBrowserConnection()
        }
        
        if (mediaBrowser == null || mediaBrowser?.isConnected == false) {
            initializeMediaBrowserCompatConnection()
        }
        
        // Register the broadcast receiver for playback state changes
        val filter = IntentFilter("ACTION_PLAYBACK_STATE_CHANGED")
        registerReceiver(playbackStateReceiver, filter)
        Log.d(TAG, "Registered playback state broadcast receiver")
    }
    
    override fun onStop() {
        super.onStop()
        // Release Media3 controller
        if (controllerFuture != null) {
            val future = controllerFuture
            controllerFuture = null
            if (future != null) {
                MediaController.releaseFuture(future)
            }
        }
        
        // Release MediaBrowserCompat connection
        mediaController?.unregisterCallback(controllerCallback)
        mediaBrowser?.disconnect()
        mediaBrowser = null
        
        // Unregister the broadcast receiver
        try {
            unregisterReceiver(playbackStateReceiver)
            Log.d(TAG, "Unregistered playback state broadcast receiver")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }
}