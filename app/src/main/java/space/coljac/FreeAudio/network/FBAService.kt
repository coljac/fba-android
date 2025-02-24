package space.coljac.FreeAudio.network

import android.text.Html
import android.util.Log
import com.google.gson.Gson
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
        
        // Extract JSON from HTML response
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
        
        val jsonStr = response.substring(jsonStartIndex, jsonEndIndex)
            .trim()
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
            .trim(';', ' ', '\n', '\r')  // Remove any trailing semicolons or whitespace
        
        Log.d(TAG, "Processing JSON string length: ${jsonStr.length}")
        Log.d(TAG, "JSON string start: ${jsonStr.take(100)}")
        Log.d(TAG, "JSON string end: ${jsonStr.takeLast(100)}")
        
        try {
            val jsonObject = gson.fromJson(jsonStr, JsonObject::class.java)
            val results = jsonObject.getAsJsonArray("results")
            
            val talks = results.map { element ->
                val talk = element.asJsonObject
                val tracks = talk.getAsJsonArray("tracks")
                Talk(
                    id = talk.get("cat_num").asString,
                    title = decodeHtml(talk.get("title").asString),
                    speaker = decodeHtml(talk.get("speaker").asString),
                    year = talk.get("year").asString,
                    blurb = decodeHtml(talk.get("blurb").asString),
                    imageUrl = baseUrl + talk.get("image_url").asString,
                    tracks = tracks?.map { trackElement ->
                        val track = trackElement.asJsonObject
                        Track(
                            title = decodeHtml(track.get("track_title").asString),
                            number = track.get("track_num").asString.toInt(),
                            path = baseUrl + track.get("path").asString,
                            duration = track.get("time").asString
                        )
                    } ?: emptyList()
                )
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
} 