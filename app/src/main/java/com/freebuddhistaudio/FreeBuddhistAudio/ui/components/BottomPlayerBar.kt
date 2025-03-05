package com.freebuddhistaudio.FreeBuddhistAudio.ui.components

import androidx.compose.foundation.background
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

@Composable
fun BottomPlayerBar(
    viewModel: AudioViewModel,
    onTalkClick: (Talk) -> Unit,
    modifier: Modifier = Modifier,
    currentScreen: String = ""
) {
    val currentTalk by viewModel.currentTalk.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()

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
                )
                
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
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