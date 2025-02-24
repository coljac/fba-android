package space.coljac.FreeAudio.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TalkDetailScreen(
    viewModel: AudioViewModel,
    talkId: String,
    onNavigateUp: () -> Unit
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    
    LaunchedEffect(talkId) {
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

                // Description
                Text(
                    text = talk.blurb,
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Download/Play Section
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { 
                            // Only download, don't play
                            if (!isDownloaded) viewModel.downloadTalk(talk) 
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isDownloaded && downloadProgress == null
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            when {
                                downloadProgress != null -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text("${(downloadProgress?.times(100))?.toInt()}%")
                                }
                                isDownloaded -> {
                                    Icon(Icons.Default.Check, "Downloaded")
                                    Text("Downloaded")
                                }
                                else -> {
                                    Icon(Icons.Default.Download, "Download")
                                    Text("Download")
                                }
                            }
                        }
                    }

                    Button(
                        onClick = { viewModel.playTalk(talk) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                "Play"
                            )
                            Text(if (playbackState.isPlaying) "Pause" else "Play")
                        }
                    }
                }

                // Playback Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.skipToPreviousTrack() }) {
                        Icon(Icons.Default.SkipPrevious, "Previous Track")
                    }
                    IconButton(onClick = { viewModel.seekBackward() }) {
                        Icon(Icons.Rounded.FastRewind, "Seek Backward")
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
                        Icon(Icons.Rounded.FastForward, "Seek Forward")
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
            title = { Text("Delete Download") },
            text = { Text("Are you sure you want to delete this downloaded talk?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        currentTalk?.let { talk ->
                            viewModel.deleteTalk(talk)
                            onNavigateUp()
                        }
                        showDeleteConfirmation = false
                    }
                ) {
                    Text("Delete")
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