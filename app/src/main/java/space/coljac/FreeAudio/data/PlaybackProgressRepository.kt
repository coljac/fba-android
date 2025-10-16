package space.coljac.FreeAudio.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "PlaybackProgressRepository"
private const val PREF_NAME = "playback_progress"
private const val KEY_PROGRESS_PREFIX = "progress_"

class PlaybackProgressRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    suspend fun saveProgress(progress: PlaybackProgress) = withContext(Dispatchers.IO) {
        try {
            val key = KEY_PROGRESS_PREFIX + progress.talkId
            val json = gson.toJson(progress)
            prefs.edit().putString(key, json).apply()
            Log.d(TAG, "Saved progress for talk ${progress.talkId}: track ${progress.trackIndex}, position ${progress.positionMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving progress for talk ${progress.talkId}", e)
        }
    }

    suspend fun getProgress(talkId: String): PlaybackProgress? = withContext(Dispatchers.IO) {
        try {
            val key = KEY_PROGRESS_PREFIX + talkId
            val json = prefs.getString(key, null)
            if (json != null) {
                val progress = gson.fromJson(json, PlaybackProgress::class.java)
                Log.d(TAG, "Retrieved progress for talk $talkId: track ${progress.trackIndex}, position ${progress.positionMs}ms")
                return@withContext progress
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving progress for talk $talkId", e)
        }
        return@withContext null
    }

    suspend fun clearProgress(talkId: String) = withContext(Dispatchers.IO) {
        try {
            val key = KEY_PROGRESS_PREFIX + talkId
            prefs.edit().remove(key).apply()
            Log.d(TAG, "Cleared progress for talk $talkId")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing progress for talk $talkId", e)
        }
    }

    suspend fun hasProgress(talkId: String): Boolean = withContext(Dispatchers.IO) {
        val key = KEY_PROGRESS_PREFIX + talkId
        return@withContext prefs.contains(key)
    }

    suspend fun getAllProgress(): List<PlaybackProgress> = withContext(Dispatchers.IO) {
        val progressList = mutableListOf<PlaybackProgress>()
        try {
            prefs.all.forEach { (key, value) ->
                if (key.startsWith(KEY_PROGRESS_PREFIX) && value is String) {
                    try {
                        val progress = gson.fromJson(value, PlaybackProgress::class.java)
                        progressList.add(progress)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing progress for key $key", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving all progress", e)
        }
        return@withContext progressList
    }
}