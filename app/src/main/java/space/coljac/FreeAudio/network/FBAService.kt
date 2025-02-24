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
} 