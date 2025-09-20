package space.coljac.FreeAudio

import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.net.URL
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.media3.common.util.UnstableApi
import space.coljac.FreeAudio.playback.AudioService
import space.coljac.FreeAudio.ui.theme.FreeAudioTheme
import space.coljac.FreeAudio.viewmodel.AudioViewModel
import space.coljac.FreeAudio.navigation.AppNavigation

private const val TAG = "MainActivity"

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
class MainActivity : ComponentActivity() {
    private val viewModel: AudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Ensure the MediaSessionService is started so the controller can connect
        try {
            startService(Intent(this, AudioService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioService", e)
        }

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
    
    // No compat or direct controller management here; ViewModel owns the controller
    
    override fun onStart() {
        super.onStart()
        // Nothing to do; ViewModel manages Media3 controller
    }
    
    override fun onStop() {
        super.onStop()
        // Nothing to release here
    }
}
