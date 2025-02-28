package space.coljac.FreeAudio.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.google.gson.Gson
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream
import space.coljac.FreeAudio.network.FBAService

private const val TAG = "TalkRepository"

class TalkRepository(private val context: Context) {
    private val talksDirectory = File(context.filesDir, "talks")
    private val metadataDirectory = File(context.filesDir, "metadata")
    private val gson = Gson()
    private val recentPlaysFile = File(context.filesDir, "recent_plays.json")
    private val favoritesFile = File(context.filesDir, "favorites.json")
    private val maxRecentPlays = 10
    private val fbaService = FBAService()

    init {
        talksDirectory.mkdirs()
        metadataDirectory.mkdirs()
    }

    suspend fun downloadTalk(talk: Talk): Flow<Float> = callbackFlow {
        // Use callbackFlow which is designed for this kind of operation
        
        // Create a separate coroutine for the download operation
        val downloadJob = CoroutineScope(Dispatchers.IO).launch {
            val talkDir = File(talksDirectory, talk.id)
            talkDir.mkdirs()
            
            try {
                // Emit initial progress
                trySend(0.01f)
                
                // First save talk metadata to ensure we have it even if download fails
                saveTalkMetadata(talk)
                
                // Also save metadata for favorites
                saveTalkMetadataForFavorite(talk)
                
                // Download the ZIP file containing all tracks
                val zipUrl = URL("https://www.freebuddhistaudio.com/talks/mp3zips/${talk.id}.zip")
                val connection = zipUrl.openConnection().apply {
                    // Add a timeout to prevent hanging
                    connectTimeout = 30000
                    readTimeout = 30000
                }
                
                val totalBytes = connection.contentLength.toFloat()
                if (totalBytes <= 0) {
                    Log.w(TAG, "Could not determine content length, using estimate")
                    // Use an estimate if content length is not available
                    trySend(0.05f)
                }
                
                var downloadedBytes = 0f
                var trackCount = 0
                Log.i(TAG, "Downloading talk ${talk.id} from $zipUrl")
                
                // Report some progress before we start extracting
                trySend(0.1f)
                
                connection.getInputStream().use { input ->
                    ZipInputStream(input).use { zipInput ->
                        var entry = zipInput.nextEntry
                        while (entry != null) {
                            // Extract only MP3 files
                            if (!entry.isDirectory && entry.name.endsWith(".mp3")) {
                                val trackNumber = entry.name.substringBefore(".mp3").toIntOrNull()
                                if (trackNumber != null) {
                                    val trackFile = File(talkDir, "$trackNumber.mp3")
                                    trackCount++
                                    
                                    FileOutputStream(trackFile).use { output ->
                                        val buffer = ByteArray(8192)
                                        var bytes = zipInput.read(buffer)
                                        var lastEmitTime = System.currentTimeMillis()
                                        
                                        while (bytes >= 0) {
                                            output.write(buffer, 0, bytes)
                                            downloadedBytes += bytes
                                            
                                            // Calculate progress - handle case where content length is unknown
                                            val progress = if (totalBytes > 0) {
                                                downloadedBytes / totalBytes
                                            } else {
                                                // More gradual progress based on completed tracks and bytes
                                                val trackProgress = trackCount.toFloat() / talk.tracks.size.coerceAtLeast(1)
                                                // Start at 10% and gradually increase
                                                0.1f + (0.85f * trackProgress)
                                            }
                                            
                                            // Don't emit too frequently to avoid overwhelming the UI
                                            val currentTime = System.currentTimeMillis()
                                            if (currentTime - lastEmitTime > 100) {
                                                // Ensure progress is always increasing
                                                val safeProgress = progress.coerceIn(0.01f, 0.99f)
                                                trySend(safeProgress)
                                                lastEmitTime = currentTime
                                                
                                                // Log more detailed progress information
                                                if (trackCount % 2 == 0) {
                                                    Log.d(TAG, "Download progress: ${(safeProgress * 100).toInt()}%, track $trackCount/${talk.tracks.size}")
                                                }
                                            }
                                            
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
                
                // Ensure we emit 100% at the end
                trySend(1.0f)
                Log.i(TAG, "Download completed for talk ${talk.id}, extracted $trackCount tracks")
                close() // Close the channel when done
                
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading talk: ${e.message}", e)
                
                // Clean up any partially downloaded files if error occurs
                if (talkDir.exists()) {
                    talkDir.listFiles()?.forEach { it.delete() }
                    talkDir.delete()
                }
                
                close(e) // Close with error
            }
        }
        
        // When the flow collection is cancelled, cancel the download job
        awaitClose {
            downloadJob.cancel()
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
        val talkDir = File(talksDirectory, talkId)
        val metadataFile = File(talksDirectory, "$talkId.json")
        return talkDir.exists() && metadataFile.exists() && talkDir.listFiles()?.isNotEmpty() == true
    }

    suspend fun getDownloadedTalks(): List<Talk> = withContext(Dispatchers.IO) {
        val favoriteIds = getFavoriteIds()
        talksDirectory.listFiles { file -> file.isDirectory }
            ?.mapNotNull { dir ->
                val metadataFile = File(talksDirectory, "${dir.name}.json")
                if (metadataFile.exists()) {
                    try {
                        val talk = gson.fromJson(metadataFile.readText(), Talk::class.java)
                        // Check if this talk is in favorites
                        talk.copy(isFavorite = favoriteIds.contains(talk.id))
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
        
        val favoriteIds = getFavoriteIds()
        try {
            val talks = gson.fromJson(recentPlaysFile.readText(), Array<Talk>::class.java).toList()
            // Update favorite status for each talk
            talks.map { talk -> talk.copy(isFavorite = favoriteIds.contains(talk.id)) }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading recent plays", e)
            emptyList()
        }
    }

    suspend fun getTalkById(talkId: String): Talk? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Getting talk by ID: $talkId")
        
        // First check if we need to fetch updated track details
        var shouldFetchFromNetwork = true
        
        // First check in the main directory for downloaded talks
        val downloadedMetadataFile = File(talksDirectory, "$talkId.json")
        
        // Then check the metadata directory for favorited talks
        val favoriteMetadataFile = File(metadataDirectory, "$talkId.json")
        
        // Use the downloaded file if available, otherwise use the favorite metadata
        val metadataFile = if (downloadedMetadataFile.exists()) downloadedMetadataFile else favoriteMetadataFile
        
        val localTalk = if (metadataFile.exists()) {
            try {
                Log.d(TAG, "Found local metadata for talk $talkId in ${metadataFile.absolutePath}")
                val talk = gson.fromJson(metadataFile.readText(), Talk::class.java)
                
                // Check if this talk has detailed track information
                shouldFetchFromNetwork = talk.tracks.isEmpty() || 
                    talk.tracks.firstOrNull()?.trackId.isNullOrEmpty() ||
                    talk.tracks.firstOrNull()?.durationSeconds == 0
                
                Log.d(TAG, "Talk $talkId has ${talk.tracks.size} tracks locally, shouldFetchFromNetwork=$shouldFetchFromNetwork")
                
                // Check if this talk is a favorite
                val isFavorite = isFavorite(talkId)
                talk.copy(isFavorite = isFavorite)
            } catch (e: Exception) {
                Log.e(TAG, "Error reading talk metadata", e)
                null
            }
        } else {
            Log.d(TAG, "No local metadata found for talk $talkId")
            null
        }
        
        // If we don't have the talk locally or we need updated track details, fetch from network
        if (localTalk == null || shouldFetchFromNetwork) {
            try {
                Log.d(TAG, "Fetching talk details from network for talk $talkId")
                val networkTalk = fbaService.getTalkDetails(talkId)
                
                if (networkTalk != null) {
                    Log.d(TAG, "Received network talk with ID: $talkId, tracks: ${networkTalk.tracks.size}")
                    
                    // Update with favorite status from local data
                    val isFavorite = isFavorite(talkId)
                    val updatedTalk = networkTalk.copy(isFavorite = isFavorite)
                    
                    // Save the updated metadata
                    saveTalkMetadata(updatedTalk)
                    if (isFavorite) {
                        saveTalkMetadataForFavorite(updatedTalk)
                    }
                    
                    Log.d(TAG, "Returning updated talk with ${updatedTalk.tracks.size} tracks")
                    return@withContext updatedTalk
                } else {
                    Log.e(TAG, "Network returned null for talk $talkId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching talk details from network: ${e.message}")
                // Fall back to local talk if available
            }
        } else {
            Log.d(TAG, "Using local talk data, not fetching from network")
        }
        
        Log.d(TAG, "Returning local talk with ${localTalk?.tracks?.size ?: 0} tracks")
        return@withContext localTalk
    }
    
    suspend fun toggleFavorite(talk: Talk): Talk = withContext(Dispatchers.IO) {
        val favoriteIds = getFavoriteIds().toMutableSet()
        val newIsFavorite = if (favoriteIds.contains(talk.id)) {
            favoriteIds.remove(talk.id)
            false
        } else {
            favoriteIds.add(talk.id)
            // Always save metadata when favoriting a talk
            saveTalkMetadataForFavorite(talk)
            true
        }
        saveFavoriteIds(favoriteIds)
        talk.copy(isFavorite = newIsFavorite)
    }
    
    private fun saveTalkMetadataForFavorite(talk: Talk) {
        val metadataFile = File(metadataDirectory, "${talk.id}.json")
        metadataFile.writeText(gson.toJson(talk))
        Log.d(TAG, "Saved metadata for favorite talk: ${talk.id}")
    }
    
    private suspend fun isFavorite(talkId: String): Boolean = withContext(Dispatchers.IO) {
        getFavoriteIds().contains(talkId)
    }
    
    private suspend fun getFavoriteIds(): Set<String> = withContext(Dispatchers.IO) {
        if (!favoritesFile.exists()) return@withContext emptySet()
        try {
            gson.fromJson(favoritesFile.readText(), Array<String>::class.java).toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading favorites", e)
            emptySet()
        }
    }
    
    private suspend fun saveFavoriteIds(favoriteIds: Set<String>) = withContext(Dispatchers.IO) {
        favoritesFile.writeText(gson.toJson(favoriteIds.toTypedArray()))
    }
    
    suspend fun getFavoriteTalks(): List<Talk> = withContext(Dispatchers.IO) {
        val favoriteIds = getFavoriteIds()
        Log.d(TAG, "Found ${favoriteIds.size} favorite IDs: $favoriteIds")
        
        if (favoriteIds.isEmpty()) return@withContext emptyList()
        
        val result = favoriteIds.mapNotNull { id ->
            val talk = getTalkById(id)
            if (talk == null) {
                Log.d(TAG, "Could not find metadata for favorite talk with ID: $id")
            }
            talk
        }
        
        Log.d(TAG, "Returning ${result.size} favorite talks")
        return@withContext result
    }
    
    // Debug method to get favorite IDs
    suspend fun debugGetFavoriteIds(): Set<String> = getFavoriteIds()
} 