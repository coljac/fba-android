package space.coljac.FreeAudio.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
    init {
        talksDirectory.mkdirs()
    }

    suspend fun downloadTalk(talk: Talk): Flow<Float> = flow {
        val talkDir = File(talksDirectory, talk.id)
        talkDir.mkdirs()

        try {
            talk.tracks.forEachIndexed { index, track ->
                val trackFile = File(talkDir, "${index + 1}.mp3")
                if (!trackFile.exists()) {
                    val url = URL(track.path)
                    val connection = url.openConnection()
                    val totalBytes = connection.contentLength.toFloat()
                    var downloadedBytes = 0f

                    withContext(Dispatchers.IO) {
                        connection.getInputStream().use { input ->
                            FileOutputStream(trackFile).use { output ->
                                val buffer = ByteArray(8192)
                                var bytes = input.read(buffer)
                                while (bytes >= 0) {
                                    output.write(buffer, 0, bytes)
                                    downloadedBytes += bytes
                                    emit(downloadedBytes / totalBytes)
                                    bytes = input.read(buffer)
                                }
                            }
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
    }

    private fun saveTalkMetadata(talk: Talk) {
        val metadataFile = File(talksDirectory, "${talk.id}.json")
        metadataFile.writeText(gson.toJson(talk))
    }

    fun getLocalTalkPath(talkId: String, trackNumber: Int): String? {
        val file = File(talksDirectory, "$talkId/${trackNumber}.mp3")
        return if (file.exists()) file.absolutePath else null
    }

    fun isDownloaded(talkId: String): Boolean {
        return File(talksDirectory, talkId).exists()
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
} 