package com.freebuddhistaudio.FreeBuddhistAudio.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import com.google.gson.Gson
import com.freebuddhistaudio.FreeBuddhistAudio.network.FBAService

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
                saveTalkMetadataForFavorite(talk)
                
                // Number of retries and current attempt counter
                val maxRetries = 3
                var currentRetry = 0
                var downloadSuccess = false
                var lastException: Exception? = null
                
                // Add more retry logic for robustness
                while (currentRetry < maxRetries && !downloadSuccess) {
                    try {
                        if (currentRetry > 0) {
                            Log.w(TAG, "Retrying download (attempt ${currentRetry + 1}/$maxRetries)...")
                            // Send a progress update so the user knows we're retrying
                            trySend(0.05f * currentRetry)
                            // Wait a bit before retrying to avoid hammering the server
                            delay(1000L * currentRetry)
                        }
                        
                        // Use an HttpURLConnection for more control
                        val zipUrl = URL("https://www.freebuddhistaudio.com/talks/mp3zips/${talk.id}.zip")
                        val connection = zipUrl.openConnection() as HttpURLConnection
                        
                        with(connection) {
                            connectTimeout = 30000 // 30 seconds
                            readTimeout = 30000 // 30 seconds
                            useCaches = false
                            defaultUseCaches = false
                            requestMethod = "GET"
                            instanceFollowRedirects = true
                            setRequestProperty("Connection", "close") // Don't keep connection alive
                            setRequestProperty("User-Agent", "FreeAudio-App/1.0")
                        }
                        
                        // Connect first to handle redirects and get content length
                        connection.connect()
                        
                        // Check for HTTP errors
                        val responseCode = connection.responseCode
                        if (responseCode != HttpURLConnection.HTTP_OK) {
                            throw IOException("HTTP error code: $responseCode - ${connection.responseMessage}")
                        }
                        
                        val totalBytes = connection.contentLength.toFloat()
                        if (totalBytes <= 0) {
                            Log.w(TAG, "Could not determine content length, using estimate")
                            trySend(0.05f)
                        }
                        
                        var downloadedBytes = 0f
                        var trackCount = 0
                        Log.i(TAG, "Downloading talk ${talk.id} (attempt ${currentRetry + 1}) from $zipUrl")
                        
                        // Report progress before starting extraction
                        trySend(0.1f)
                        
                        // Use buffered streams for better performance
                        val inputStream = BufferedInputStream(connection.inputStream, 8192)
                        inputStream.use { input ->
                            ZipInputStream(input).use { zipInput ->
                                var entry = zipInput.nextEntry
                                while (entry != null) {
                                    // Process entries with small delay between each to avoid overwhelming the system
                                    yield() // Allow coroutine cancellation
                                    
                                    // Extract only MP3 files
                                    if (!entry.isDirectory && entry.name.endsWith(".mp3")) {
                                        val trackNumber = entry.name.substringBefore(".mp3").toIntOrNull()
                                        if (trackNumber != null) {
                                            val trackFile = File(talkDir, "$trackNumber.mp3")
                                            trackCount++
                                            
                                            // Use buffered output for better performance
                                            BufferedOutputStream(FileOutputStream(trackFile), 8192).use { output ->
                                                val buffer = ByteArray(8192)
                                                var bytes = zipInput.read(buffer)
                                                var lastEmitTime = System.currentTimeMillis()
                                                
                                                while (bytes >= 0) {
                                                    yield() // Allow coroutine cancellation during long downloads
                                                    
                                                    output.write(buffer, 0, bytes)
                                                    downloadedBytes += bytes
                                                    
                                                    // Calculate progress with better handling of unknown content length
                                                    val progress = if (totalBytes > 0) {
                                                        downloadedBytes / totalBytes
                                                    } else {
                                                        // More gradual progress based on completed tracks 
                                                        val trackProgress = trackCount.toFloat() / talk.tracks.size.coerceAtLeast(1)
                                                        // Start at 10% and gradually increase
                                                        0.1f + (0.85f * trackProgress)
                                                    }
                                                    
                                                    // Don't emit too frequently to avoid overwhelming the UI
                                                    val currentTime = System.currentTimeMillis()
                                                    if (currentTime - lastEmitTime > 200) {
                                                        // Ensure progress is always increasing
                                                        val safeProgress = progress.coerceIn(0.01f, 0.99f)
                                                        trySend(safeProgress)
                                                        lastEmitTime = currentTime
                                                        
                                                        // Log progress occasionally
                                                        if (trackCount % 2 == 0) {
                                                            Log.d(TAG, "Download progress: ${(safeProgress * 100).toInt()}%, track $trackCount/${talk.tracks.size}")
                                                        }
                                                    }
                                                    
                                                    try {
                                                        bytes = zipInput.read(buffer)
                                                    } catch (e: Exception) {
                                                        Log.w(TAG, "Error reading zip entry: ${e.message}")
                                                        throw e
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    
                                    try {
                                        zipInput.closeEntry()
                                        entry = zipInput.nextEntry
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Error getting next zip entry: ${e.message}")
                                        throw e
                                    }
                                }
                            }
                        }
                        
                        // If we get here without exceptions, download succeeded
                        downloadSuccess = true
                        trySend(1.0f) // 100% progress
                        Log.i(TAG, "Download completed for talk ${talk.id}, extracted $trackCount tracks")
                        
                    } catch (e: Exception) {
                        lastException = e
                        Log.e(TAG, "Error downloading talk (attempt ${currentRetry + 1}): ${e.message}", e)
                        currentRetry++
                        
                        // If this was our last retry and it failed, clean up
                        if (currentRetry >= maxRetries) {
                            Log.e(TAG, "All download retries failed for talk ${talk.id}")
                            // Clean up any partially downloaded files
                            if (talkDir.exists()) {
                                talkDir.listFiles()?.forEach { it.delete() }
                                talkDir.delete()
                            }
                        }
                    }
                }
                
                // After all retries, check if we succeeded or need to report final error
                if (downloadSuccess) {
                    close() // Close channel normally on success
                } else {
                    // Close with the last exception we encountered
                    close(lastException ?: IOException("Failed to download after $maxRetries attempts"))
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in download flow: ${e.message}", e)
                
                // Clean up any partially downloaded files
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