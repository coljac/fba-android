package com.freebuddhistaudio.FreeBuddhistAudio

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import com.freebuddhistaudio.FreeBuddhistAudio.playback.AudioService
import com.freebuddhistaudio.FreeBuddhistAudio.ui.theme.FreeAudioTheme
import com.freebuddhistaudio.FreeBuddhistAudio.viewmodel.AudioViewModel
import com.freebuddhistaudio.FreeBuddhistAudio.navigation.AppNavigation
import java.net.URL

private const val TAG = "MainActivity"

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
class MainActivity : ComponentActivity() {
    private val viewModel: AudioViewModel by viewModels()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null
    
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
        
        // Initialize media controller connection
        initializeMediaController()
        
        // Test track parsing
        testTrackListParsing()
        
        setContent {
            FreeAudioTheme {
                AppNavigation(viewModel = viewModel)
            }
        }
    }
    
    private fun testTrackListParsing() {
        GlobalScope.launch {
            try {
                val talkId = "36" // Example talk ID from your JSON sample
                val baseUrl = "https://www.freebuddhistaudio.com"
                val url = "$baseUrl/audio/details?num=$talkId"
                
                Log.d("TRACK_TEST", "Fetching talk details from $url")
                
                val response = URL(url).readText()
                val jsonStart = response.indexOf("document.__FBA__.talk = ")
                
                if (jsonStart == -1) {
                    Log.e("TRACK_TEST", "Could not find JSON start marker in response")
                    return@launch
                }
                
                val jsonStartIndex = jsonStart + "document.__FBA__.talk = ".length
                val jsonEndIndex = response.indexOf(";", jsonStartIndex)
                
                if (jsonEndIndex == -1) {
                    Log.e("TRACK_TEST", "Could not find JSON end marker in response")
                    return@launch
                }
                
                val jsonStr = response.substring(jsonStartIndex, jsonEndIndex).trim()
                Log.d("TRACK_TEST", "Found talk details JSON of length: ${jsonStr.length}")
                
                try {
                    val gson = Gson()
                    val talkObject = gson.fromJson(jsonStr, JsonObject::class.java)
                    
                    // Log the talk details
                    val title = talkObject.get("title").asString
                    val speaker = talkObject.get("speaker").asString
                    Log.d("TRACK_TEST", "Talk: $title by $speaker")
                    
                    // Extract tracks
                    val tracksArray = talkObject.getAsJsonArray("tracks")
                    Log.d("TRACK_TEST", "Found ${tracksArray?.size() ?: 0} tracks")
                    
                    tracksArray?.forEach { trackElement ->
                        try {
                            val track = trackElement.asJsonObject
                            Log.d("TRACK_TEST", "Raw track: ${track.toString()}")
                            
                            val audioObj = track.getAsJsonObject("audio")
                            val mp3Path = audioObj.get("mp3").asString
                            
                            val title = if (track.has("title")) {
                                track.get("title").asString
                            } else {
                                "Unknown Track"
                            }
                            
                            // Get duration
                            val durationSeconds = if (track.has("durationSeconds")) {
                                track.get("durationSeconds").asInt
                            } else {
                                0
                            }
                            
                            // Get trackId
                            val trackId = if (track.has("trackId")) {
                                track.get("trackId").asString
                            } else {
                                "unknown"
                            }
                            
                            Log.d("TRACK_TEST", "Track: $title, mp3Path: $mp3Path, duration: $durationSeconds seconds, id: $trackId")
                        } catch (e: Exception) {
                            Log.e("TRACK_TEST", "Error parsing track: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TRACK_TEST", "Error parsing JSON: ${e.message}", e)
                    Log.e("TRACK_TEST", "JSON string: ${jsonStr.take(200)}...")
                }
            } catch (e: Exception) {
                Log.e("TRACK_TEST", "Network error: ${e.message}", e)
            }
        }
    }
    
    @UnstableApi
    private fun initializeMediaController() {
        if (controllerFuture == null) {
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
                    
                    // Set up player listeners
                    mediaController?.addListener(object : androidx.media3.common.Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            Log.d(TAG, "Main activity controller: onIsPlayingChanged: $isPlaying")
                            viewModel.syncPlaybackState(isPlaying)
                        }
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating MediaController", e)
                }
            }, MoreExecutors.directExecutor())
        }
    }
    
    override fun onStart() {
        super.onStart()
        // Re-establish connection to the media controller
        if (controllerFuture == null) {
            initializeMediaController()
        }
        
        // Register the broadcast receiver for playback state changes
        val filter = IntentFilter("ACTION_PLAYBACK_STATE_CHANGED")
        // Fix for Android 12+ security requirement: specify receiver is not exported
        registerReceiver(playbackStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
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
        
        // Unregister the broadcast receiver
        try {
            unregisterReceiver(playbackStateReceiver)
            Log.d(TAG, "Unregistered playback state broadcast receiver")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }
}