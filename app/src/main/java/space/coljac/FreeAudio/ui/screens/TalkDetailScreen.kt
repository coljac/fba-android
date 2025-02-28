package space.coljac.FreeAudio.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import space.coljac.FreeAudio.viewmodel.AudioViewModel
import space.coljac.FreeAudio.data.Talk
import space.coljac.FreeAudio.data.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TalkDetailScreen(
    viewModel: AudioViewModel,
    talkId: String,
    onNavigateUp: () -> Unit
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    
    LaunchedEffect(talkId) {
        android.util.Log.d("TalkDetailScreen", "Loading talk with ID: $talkId")
        viewModel.loadTalk(talkId)
    }

    val playbackState by viewModel.playbackState.collectAsState()
    val currentTalk by viewModel.currentTalk.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val isDownloaded by viewModel.isDownloaded.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Talk Details") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (isDownloaded) {
                        IconButton(
                            onClick = { showDeleteConfirmation = true }
                        ) {
                            Icon(Icons.Default.Delete, "Delete Download")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            currentTalk?.let { talk ->
                // Talk Info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = talk.imageUrl,
                        contentDescription = "Speaker",
                        modifier = Modifier
                            .size(80.dp)
                            .padding(end = 16.dp)
                    )
                    Column {
                        Text(
                            text = talk.title,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "${talk.speaker} (${talk.year})",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Description in a scrollable container with max height
                Text(
                    text = "Description",
                    style = MaterialTheme.typography.titleMedium
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                val scrollState = rememberScrollState()
                Text(
                    text = talk.blurb,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 100.dp)
                        .verticalScroll(scrollState)
                )

                Spacer(modifier = Modifier.height(16.dp))
                
                // Track List
                android.util.Log.d("TalkDetailScreen", "Talk has ${talk.tracks.size} tracks")
                if (talk.tracks.isNotEmpty()) {
                    android.util.Log.d("TalkDetailScreen", "Displaying track list")
                    Text(
                        text = "Tracks (${talk.tracks.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // List of tracks in a scrollable container with adaptive height
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp) // Reduced height to leave more room for controls
                    ) {
                        itemsIndexed(talk.tracks) { index, track ->
                            val isCurrentTrack = 
                                playbackState.currentTrackIndex == index && 
                                currentTalk?.id == talk.id
                                
                            TrackItem(
                                track = track,
                                trackIndex = index,
                                isPlaying = isCurrentTrack && playbackState.isPlaying,
                                onClick = { viewModel.playTrack(index) }
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Divider between tracks and actions
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant
                )
                
                // Download/Favorite Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Download button
                    OutlinedButton(
                        onClick = { 
                            if (isDownloaded) {
                                // Show confirmation dialog if already downloaded
                                showDeleteConfirmation = true
                            } else if (downloadProgress == null) {
                                // Start download if not already in progress
                                viewModel.downloadTalk(talk) 
                            }
                        },
                        modifier = Modifier.weight(1f),
                        // Always enable the button, even when downloaded (for removal)
                        enabled = downloadProgress == null,
                        colors = if (isDownloaded) {
                            // Use error color for the "Remove" button
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        }
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            when {
                                downloadProgress != null -> {
                                    // Show progress bar during download
                                    LinearProgressIndicator(
                                        progress = { downloadProgress ?: 0f },
                                        modifier = Modifier
                                            .width(24.dp)
                                            .height(2.dp)
                                    )
                                    Text(
                                        "Downloading ${(downloadProgress?.times(100))?.toInt()}%",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                isDownloaded -> {
                                    // Show remove icon when downloaded
                                    Icon(
                                        Icons.Default.Delete,
                                        "Remove Download",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text("Remove")
                                }
                                else -> {
                                    // Regular download button
                                    Icon(
                                        Icons.Default.Download,
                                        "Download",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text("Download")
                                }
                            }
                        }
                    }

                    // Favorite button
                    OutlinedButton(
                        onClick = { viewModel.toggleFavorite(talk) },
                        modifier = Modifier.weight(1f),
                        colors = if (talk.isFavorite) {
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        }
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (talk.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                if (talk.isFavorite) "Remove from Favorites" else "Add to Favorites",
                                modifier = Modifier.size(16.dp)
                            )
                            Text(if (talk.isFavorite) "Favorited" else "Favorite")
                        }
                    }
                }
                
                // Push playback controls to bottom with spacer
                Spacer(modifier = Modifier.weight(1f))
                
                // Playback Controls at bottom
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.skipToPreviousTrack() }) {
                        Icon(Icons.Default.SkipPrevious, "Previous Track")
                    }
                    IconButton(onClick = { viewModel.seekBackward() }) {
                        Icon(Icons.Default.Replay10, "Skip Back 10 Seconds")
                    }
                    FilledIconButton(
                        onClick = { viewModel.togglePlayPause() }
                    ) {
                        Icon(
                            if (playbackState.isPlaying) Icons.Default.Pause 
                            else Icons.Default.PlayArrow,
                            "Play/Pause"
                        )
                    }
                    IconButton(onClick = { viewModel.seekForward() }) {
                        Icon(Icons.Default.Forward10, "Skip Forward 10 Seconds")
                    }
                    IconButton(onClick = { viewModel.skipToNextTrack() }) {
                        Icon(Icons.Default.SkipNext, "Next Track")
                    }
                }

            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Remove Download") },
            text = { Text("Are you sure you want to remove this downloaded talk? You'll need to download it again to listen offline.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        currentTalk?.let { talk ->
                            viewModel.deleteTalk(talk)
                            // We don't navigate up anymore - just stay on the page
                            // The UI will update to show the Download button
                        }
                        showDeleteConfirmation = false
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun TrackItem(
    track: Track,
    trackIndex: Int,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    // No need for this state, we'll use the isPlaying parameter directly
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            // Track content
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Track number or Play/Pause icon
                if (isPlaying) {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = "Currently Playing",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(24.dp)
                            .padding(end = 8.dp)
                    )
                } else {
                    Text(
                        text = "${trackIndex + 1}.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.width(32.dp)
                    )
                }
                
                // Track title and duration
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = track.title.ifEmpty { "Track ${trackIndex + 1}" },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    
                    Text(
                        text = if (track.duration.isNotEmpty()) track.duration else "Unknown duration",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) 
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Play button
                IconButton(onClick = onClick) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play"
                    )
                }
            }
            
            // Progress indicator (only visible when playing)
            if (isPlaying) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = true,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut()
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .padding(horizontal = 8.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
} 