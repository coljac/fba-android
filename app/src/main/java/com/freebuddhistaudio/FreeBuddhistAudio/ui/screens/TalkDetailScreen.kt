package com.freebuddhistaudio.FreeBuddhistAudio.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.freebuddhistaudio.FreeBuddhistAudio.viewmodel.AudioViewModel
import com.freebuddhistaudio.FreeBuddhistAudio.data.Talk
import com.freebuddhistaudio.FreeBuddhistAudio.data.Track

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
    val downloadError by viewModel.downloadError.collectAsState()
    val isInAutoMode by viewModel.isInAutoMode.collectAsState()
    
    // Log to verify auto mode state and force a refresh if needed
    LaunchedEffect(isInAutoMode) {
        android.util.Log.d("TalkDetailScreen", "isInAutoMode: $isInAutoMode")
    }

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
                        IconButton(onClick = { showDeleteConfirmation = true }) {
                            Icon(Icons.Default.Delete, "Delete Download")
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column {
                // Download/Favorite Buttons (fixed)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            currentTalk?.let { talk ->
                                if (isDownloaded) {
                                    showDeleteConfirmation = true
                                } else if (downloadProgress == null) {
                                    viewModel.downloadTalk(talk)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = downloadProgress == null,
                        colors = if (isDownloaded) {
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
                                    LinearProgressIndicator(
                                        progress = downloadProgress ?: 0f,
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
                                    Icon(
                                        Icons.Default.Delete,
                                        "Remove Download",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text("Remove")
                                }
                                else -> {
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

                    OutlinedButton(
                        onClick = { currentTalk?.let { viewModel.toggleFavorite(it) } },
                        modifier = Modifier.weight(1f),
                        colors = if (currentTalk?.isFavorite == true) {
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
                                imageVector = if (currentTalk?.isFavorite == true)
                                    Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (currentTalk?.isFavorite == true)
                                    "Remove from Favorites" else "Add to Favorites",
                                modifier = Modifier.size(16.dp)
                            )
                            Text(if (currentTalk?.isFavorite == true) "Favorited" else "Favorite")
                        }
                    }
                }
                // Player controls - different styles for auto and phone mode
                if (isInAutoMode) {
                    // Enhanced Player Controls for Android Auto - large and prominent
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        elevation = CardDefaults.cardElevation(
                            defaultElevation = 4.dp
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Track info
                            playbackState.currentTrack?.let { track ->
                                Text(
                                    text = track.title.ifEmpty { "Track ${playbackState.currentTrackIndex + 1}" },
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                            
                            // Main controls row - LARGE for Auto
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Previous track - larger
                                FilledTonalIconButton(
                                    onClick = { viewModel.skipToPreviousTrack() },
                                    modifier = Modifier.size(56.dp)
                                ) {
                                    Icon(
                                        Icons.Default.SkipPrevious, 
                                        contentDescription = "Previous Track",
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                
                                // Skip backward 10s - larger
                                FilledTonalIconButton(
                                    onClick = { viewModel.seekBackward() },
                                    modifier = Modifier.size(56.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Replay10, 
                                        contentDescription = "Skip Back 10 Seconds",
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                
                                // Play/Pause - extra large and prominent
                                FilledIconButton(
                                    onClick = { viewModel.togglePlayPause() },
                                    modifier = Modifier.size(72.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(
                                        if (playbackState.isPlaying) Icons.Default.Pause 
                                        else Icons.Default.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                                
                                // Skip forward 10s - larger
                                FilledTonalIconButton(
                                    onClick = { viewModel.seekForward() },
                                    modifier = Modifier.size(56.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Forward10, 
                                        contentDescription = "Skip Forward 10 Seconds",
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                
                                // Next track - larger
                                FilledTonalIconButton(
                                    onClick = { viewModel.skipToNextTrack() },
                                    modifier = Modifier.size(56.dp)
                                ) {
                                    Icon(
                                        Icons.Default.SkipNext, 
                                        contentDescription = "Next Track",
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Regular size controls for phone mode
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
                                if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            currentTalk?.let { talk ->
                // Talk Info & Description
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

                // Only show description in phone mode
                if (!isInAutoMode) {
                    Text(
                        text = "Description",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val descriptionScrollState = rememberScrollState()
                    Text(
                        text = talk.blurb,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 100.dp)
                            .verticalScroll(descriptionScrollState)
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                }

                if (talk.tracks.isNotEmpty()) {
                    Text(
                        text = "Tracks (${talk.tracks.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Scrollable Track List taking up available space
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    Column {
                        talk.tracks.forEachIndexed { index, track ->
                            val isCurrentTrack =
                                playbackState.currentTrackIndex == index &&
                                currentTalk?.id == talk.id
                            TrackItem(
                                track = track,
                                trackIndex = index,
                                isPlaying = isCurrentTrack && playbackState.isPlaying,
                                onClick = { viewModel.playTrack(index) }
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }

    // Show download error dialog if there's an error
    downloadError?.let { errorMessage ->
        AlertDialog(
            onDismissRequest = { viewModel.clearDownloadError() },
            title = { Text("Download Failed") },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearDownloadError() }) {
                    Text("OK")
                }
            }
        )
    }

    // Delete Confirmation Dialog
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = track.title.ifEmpty { "Track ${trackIndex + 1}" },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isPlaying)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (track.duration.isNotEmpty()) track.duration else "Unknown duration",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isPlaying)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onClick) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play"
                    )
                }
            }
            if (isPlaying) {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(),
                    exit = fadeOut()
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

