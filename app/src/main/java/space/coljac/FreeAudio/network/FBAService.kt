package space.coljac.FreeAudio.network
// Issues: look to mp3 file; fetch list of tracks another way
// https://www.freebuddhistaudio.com/audio/details?num=OM780
import android.text.Html
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.coljac.FreeAudio.data.SearchResponse
import space.coljac.FreeAudio.data.Talk
import space.coljac.FreeAudio.data.Track
import java.net.URL
import java.net.URLEncoder

private const val TAG = "FBAService"

class FBAService {
    private val baseUrl = "https://www.freebuddhistaudio.com"
    private val gson = Gson()
    
    private fun decodeHtml(html: String): String {
        return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
    }
    
    suspend fun search(query: String): SearchResponse = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/search?s=0&r=10&b=p&q=$encodedQuery&t=audio"
        
        val response = URL(url).readText()
        Log.d(TAG, "Original response length: ${response.length}")
        
        val jsonStart = response.indexOf("document.__FBA__.search = ")
        if (jsonStart == -1) {
            Log.e(TAG, "Could not find JSON start marker in response")
            throw Exception("Invalid response format")
        }
        
        val jsonStartIndex = jsonStart + "document.__FBA__.search = ".length
        val jsonEndIndex = response.indexOf("document.__FBA__.textSearch", jsonStartIndex)
        if (jsonEndIndex == -1) {
            Log.e(TAG, "Could not find JSON end marker in response")
            throw Exception("Invalid response format")
        }

        // val jsonEndIndex = response.indexOf(";", jsonStartIndex).let { semicolon ->
            // val nextScript = response.indexOf("<script", jsonStartIndex)
            // when {
                // semicolon == -1 -> nextScript
                // nextScript == -1 -> semicolon
                // else -> minOf(semicolon, nextScript)
            // }
        // }

        Log.d(TAG, "JSON start index: $jsonStartIndex, end index: $jsonEndIndex, length: ${jsonEndIndex - jsonStartIndex}")
        
        val jsonStr = response.substring(jsonStartIndex, jsonEndIndex)
            .trim()
            // .replace("&amp;", "&")
            // .replace("&quot;", "\"")
            // .replace("&lt;", "<")
            // .replace("&gt;", ">")
            // .replace(Regex("\\r\\n|\\r|\\n"), "\\n")
            // .replace("\\", "\\\\")
            // .replace("\"", "\\\"")
            // .trim(';', ' ', '\n', '\r')

        Log.d(TAG, "Processed JSON length: ${jsonStr.length}")
        
        // Log the problematic area
        if (jsonStr.length > 2383) {
            val start = maxOf(0, 2383 - 50)
            val end = minOf(jsonStr.length, 2383 + 50)
            Log.d(TAG, "JSON around position 2383: '${jsonStr.substring(start, end)}'")
            Log.d(TAG, "Character at 2383: '${jsonStr[2383]}'")
        }
        
        try {
            val jsonObject = gson.fromJson(jsonStr, JsonObject::class.java)
            Log.d(TAG, "Successfully parsed JSON object")
            val results = jsonObject.getAsJsonArray("results")
            Log.d(TAG, "Found ${results.size()} results")
            
            val talks = results.mapNotNull { element ->
                try {
                    val talk = element.asJsonObject
                    val tracks = talk.getAsJsonArray("tracks")
                    
                    Talk(
                        id = talk.get("cat_num").asString,
                        title = decodeHtml(talk.get("title").asString),
                        speaker = decodeHtml(talk.get("speaker").asString),
                        year = talk.get("year").asString,
                        blurb = decodeHtml(talk.get("blurb").asString),
                        imageUrl = baseUrl + talk.get("image_url").asString,
                        tracks = (tracks ?: JsonArray()).mapNotNull { trackElement ->
                            try {
                                val track = trackElement.asJsonObject
                                Track(
                                    title = decodeHtml(track.get("track_title").asString),
                                    number = track.get("track_num").asString.toInt(),
                                    path = baseUrl + track.get("path").asString,
                                    duration = track.get("time").asString
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse track in talk ${talk.get("title").asString}", e)
                                null
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse talk", e)
                    null
                }
            }
            
            SearchResponse(jsonObject.get("total").asInt, talks)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parsing error", e)
            Log.e(TAG, "JSON string was: $jsonStr")
            throw Exception("Failed to parse search results: ${e.message}")
        }
    }
    
    suspend fun getAudioUrl(talkId: String): String = withContext(Dispatchers.IO) {
        "$baseUrl/talks/mp3zips/$talkId.zip"
    }
    
    suspend fun getTalkDetails(talkId: String): Talk? = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/audio/details?num=$talkId"
            val response = URL(url).readText()
            
            Log.d(TAG, "Fetched talk details from $url")
            
            // Extract talk data from the JavaScript object
            val jsonStart = response.indexOf("document.__FBA__.talk = ")
            if (jsonStart == -1) {
                Log.e(TAG, "Could not find talk JSON start marker in response")
                return@withContext null
            }
            
            val jsonStartIndex = jsonStart + "document.__FBA__.talk = ".length
            val jsonEndIndex = response.indexOf(";", jsonStartIndex)
            if (jsonEndIndex == -1) {
                Log.e(TAG, "Could not find talk JSON end marker in response")
                return@withContext null
            }
            
            val jsonStr = response.substring(jsonStartIndex, jsonEndIndex).trim()
            Log.d(TAG, "Found talk details JSON, length: ${jsonStr.length}")
            
            try {
                val talkObject = gson.fromJson(jsonStr, JsonObject::class.java)
                Log.d(TAG, "Successfully parsed talk object for ID: $talkId")
                
                // Extract track list
                val tracksArray = talkObject.getAsJsonArray("tracks")
                Log.d(TAG, "Found tracks array with ${tracksArray?.size() ?: 0} tracks")
                
                val tracks = if (tracksArray != null && tracksArray.size() > 0) {
                    tracksArray.mapIndexed { index, trackElement ->
                        try {
                            val track = trackElement.asJsonObject
                            
                            // Log the raw track JSON for debugging
                            Log.d(TAG, "Raw track ${index + 1} JSON: ${track.toString()}")
                            
                            val audioObj = track.getAsJsonObject("audio")
                            val mp3Path = audioObj.get("mp3").asString
                            
                            val title = if (track.has("title")) {
                                track.get("title").asString
                            } else {
                                "Track ${index + 1}"
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
                                "track_${index}"
                            }
                            
                            Log.d(TAG, "Parsed track ${index + 1}: $title, path: $mp3Path, duration: $durationSeconds seconds")
                            
                            Track(
                                title = decodeHtml(title),
                                number = index + 1, // 1-based index
                                path = "$baseUrl$mp3Path",
                                duration = formatDuration(durationSeconds),
                                durationSeconds = durationSeconds,
                                trackId = trackId
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing track at index $index: ${e.message}")
                            null
                        }
                    }.filterNotNull()
                } else {
                    Log.w(TAG, "No tracks found for talk ${talkObject.get("cat_num").asString}")
                    emptyList()
                }
                
                return@withContext Talk(
                    id = talkObject.get("cat_num").asString,
                    title = decodeHtml(talkObject.get("title").asString),
                    speaker = decodeHtml(talkObject.get("speaker").asString),
                    year = talkObject.get("year").asString,
                    blurb = decodeHtml(talkObject.get("blurb").asString),
                    imageUrl = baseUrl + talkObject.get("image").asString,
                    tracks = tracks,
                    isFavorite = false // This will be updated by the repository
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing talk details JSON", e)
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching talk details", e)
            null
        }
    }
    
    private fun formatDuration(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return "$minutes:${remainingSeconds.toString().padStart(2, '0')}"
    }
} 
