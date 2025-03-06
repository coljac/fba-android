package com.freebuddhistaudio.FreeBuddhistAudio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.freebuddhistaudio.FreeBuddhistAudio.data.Talk
import com.freebuddhistaudio.FreeBuddhistAudio.viewmodel.AudioViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun BottomPlayerBar(
    viewModel: AudioViewModel,
    onTalkClick: (Talk) -> Unit,
    modifier: Modifier = Modifier,
    currentScreen: String = ""
) {
    val currentTalk by viewModel.currentTalk.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val isInAutoMode by viewModel.isInAutoMode.collectAsState()
    
    // Track progress
    var showTrackProgress by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0f) }
    
    // Update progress every second while playing
    LaunchedEffect(playbackState.isPlaying, playbackState.position, playbackState.duration) {
        // Initialize progress
        progress = if (playbackState.duration > 0) {
            (playbackState.position.toFloat() / playbackState.duration.toFloat()).coerceIn(0f, 1f)
        } else 0f
        
        // Continue updating while playing
        if (playbackState.isPlaying && playbackState.duration > 0) {
            while (isActive) {
                delay(1000) // Update every second
                progress = (playbackState.position.toFloat() / playbackState.duration.toFloat()).coerceIn(0f, 1f)
            }
        }
    }

    // Don't show bottom player bar on talk detail screen
    if (currentScreen == "TalkDetail") {
        return
    }

    currentTalk?.let { talk ->
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .wrapContentHeight(), // Only take the height we need
            tonalElevation = 8.dp
        ) {
            Column {
                // Add progress bar at the top
                if (playbackState.duration > 0) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                    )
                }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp) // Increased height for better visibility
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = talk.imageUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(56.dp)
                            .padding(4.dp)
                            .clickable { onTalkClick(talk) }
                    )
                    
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                            .clickable { onTalkClick(talk) }
                    ) {
                        Text(
                            text = talk.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = talk.speaker,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        
                        // Track title if available
                        playbackState.currentTrack?.let { track ->
                            if (track.title.isNotEmpty()) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Skip backward 10s
                        IconButton(
                            onClick = { viewModel.seekBackward() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.Replay10, 
                                contentDescription = "Skip Back 10 Seconds",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        
                        // Previous track
                        IconButton(
                            onClick = { viewModel.skipToPreviousTrack() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.SkipPrevious, 
                                contentDescription = "Previous Track",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        
                        // Play/Pause button (larger and more prominent)
                        FilledIconButton(
                            onClick = { viewModel.togglePlayPause() },
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                if (playbackState.isPlaying) Icons.Default.Pause 
                                else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        
                        // Next track
                        IconButton(
                            onClick = { viewModel.skipToNextTrack() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.SkipNext, 
                                contentDescription = "Next Track",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        
                        // Skip forward 10s
                        IconButton(
                            onClick = { viewModel.seekForward() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.Forward10, 
                                contentDescription = "Skip Forward 10 Seconds",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Auto-sized player UI designed specifically for Android Auto
 */
@Composable
fun AutoModePlayer(
    viewModel: AudioViewModel,
    modifier: Modifier = Modifier
) {
    val currentTalk by viewModel.currentTalk.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    
    // Track progress
    var progress by remember { mutableFloatStateOf(0f) }
    
    // Update progress every second while playing
    LaunchedEffect(playbackState.isPlaying, playbackState.position, playbackState.duration) {
        // Initialize progress
        progress = if (playbackState.duration > 0) {
            (playbackState.position.toFloat() / playbackState.duration.toFloat()).coerceIn(0f, 1f)
        } else 0f
        
        // Continue updating while playing
        if (playbackState.isPlaying && playbackState.duration > 0) {
            while (isActive) {
                delay(1000) // Update every second
                progress = (playbackState.position.toFloat() / playbackState.duration.toFloat()).coerceIn(0f, 1f)
            }
        }
    }

    currentTalk?.let { talk ->
        Card(
            modifier = modifier.fillMaxWidth(),
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
                // Track info - bigger text for Auto
                playbackState.currentTrack?.let { track ->
                    Text(
                        text = track.title.ifEmpty { "Track ${playbackState.currentTrackIndex + 1}" },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                
                // Progress bar
                if (playbackState.duration > 0) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                }
                
                // Main controls row - larger buttons for Auto
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Previous track
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
                    
                    // Skip backward 10s
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
                    
                    // Play/Pause
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
                    
                    // Skip forward 10s
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
                    
                    // Next track
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
    }
}