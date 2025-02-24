package space.coljac.FreeAudio.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import com.google.gson.Gson
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream

private const val TAG = "TalkRepository"

class TalkRepository(private val context: Context) {
    private val talksDirectory = File(context.filesDir, "talks")
    private val gson = Gson()
    private val recentPlaysFile = File(context.filesDir, "recent_plays.json")
    private val maxRecentPlays = 10

    init {
        talksDirectory.mkdirs()
    }

    suspend fun downloadTalk(talk: Talk): Flow<Float> = flow {
        val talkDir = File(talksDirectory, talk.id)
        talkDir.mkdirs()

        try {
            // Download the ZIP file containing all tracks
            val zipUrl = URL("https://www.freebuddhistaudio.com/talks/mp3zips/${talk.id}.zip")
            val connection = zipUrl.openConnection()
            val totalBytes = connection.contentLength.toFloat()
            var downloadedBytes = 0f
            Log.i(TAG, "Downloading talk ${talk.id} from $zipUrl")
            withContext(Dispatchers.IO) {
                connection.getInputStream().use { input ->
                    ZipInputStream(input).use { zipInput ->
                        var entry = zipInput.nextEntry
                        while (entry != null) {
                            // Extract only MP3 files
                            if (!entry.isDirectory && entry.name.endsWith(".mp3")) {
                                val trackNumber = entry.name.substringBefore(".mp3").toIntOrNull()
                                if (trackNumber != null) {
                                    val trackFile = File(talkDir, "$trackNumber.mp3")
                                    FileOutputStream(trackFile).use { output ->
                                        val buffer = ByteArray(8192)
                                        var bytes = zipInput.read(buffer)
                                        while (bytes >= 0) {
                                            output.write(buffer, 0, bytes)
                                            downloadedBytes += bytes
                                            emit(downloadedBytes / totalBytes)
                                            bytes = zipInput.read(buffer)
                                        }
                                    }
                                }
                            }
                            zipInput.closeEntry()
                            entry = zipInput.nextEntry
                        }
                    }
                }
            }
            
            // Save talk metadata
            saveTalkMetadata(talk)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading talk", e)
            throw e
        }
    }.flowOn(Dispatchers.IO)

    private fun saveTalkMetadata(talk: Talk) {
        val metadataFile = File(talksDirectory, "${talk.id}.json")
        metadataFile.writeText(gson.toJson(talk))
    }

    fun getLocalTalkPath(talkId: String, trackNumber: Int): String? {
        val file = File(talksDirectory, "$talkId/${trackNumber}.mp3")
        return if (file.exists()) file.absolutePath else null
    }

    fun isDownloaded(talkId: String): Boolean {
        val talkDir = File(talksDirectory, talkId)
        val metadataFile = File(talksDirectory, "$talkId.json")
        return talkDir.exists() && metadataFile.exists() && talkDir.listFiles()?.isNotEmpty() == true
    }

    suspend fun getDownloadedTalks(): List<Talk> = withContext(Dispatchers.IO) {
        talksDirectory.listFiles { file -> file.isDirectory }
            ?.mapNotNull { dir ->
                val metadataFile = File(talksDirectory, "${dir.name}.json")
                if (metadataFile.exists()) {
                    try {
                        gson.fromJson(metadataFile.readText(), Talk::class.java)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading talk metadata", e)
                        null
                    }
                } else null
            } ?: emptyList()
    }

    suspend fun deleteTalk(talkId: String) = withContext(Dispatchers.IO) {
        val talkDir = File(talksDirectory, talkId)
        val metadataFile = File(talksDirectory, "$talkId.json")
        talkDir.deleteRecursively()
        metadataFile.delete()
    }

    suspend fun addToRecentPlays(talk: Talk) = withContext(Dispatchers.IO) {
        val recentPlays = getRecentPlays().toMutableList()
        recentPlays.removeIf { it.id == talk.id }
        recentPlays.add(0, talk)
        if (recentPlays.size > maxRecentPlays) {
            recentPlays.removeAt(recentPlays.lastIndex)
        }
        recentPlaysFile.writeText(gson.toJson(recentPlays))
    }

    suspend fun getRecentPlays(): List<Talk> = withContext(Dispatchers.IO) {
        if (!recentPlaysFile.exists()) return@withContext emptyList()
        try {
            gson.fromJson(recentPlaysFile.readText(), Array<Talk>::class.java).toList()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading recent plays", e)
            emptyList()
        }
    }

    suspend fun getTalkById(talkId: String): Talk? = withContext(Dispatchers.IO) {
        val metadataFile = File(talksDirectory, "$talkId.json")
        if (metadataFile.exists()) {
            try {
                gson.fromJson(metadataFile.readText(), Talk::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Error reading talk metadata", e)
                null
            }
        } else null
    }
} 