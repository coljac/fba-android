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
    val isInAutoMode by viewModel.isInAutoMode.collectAsState()

    // Don't show bottom player bar on talk detail screen or in auto mode (we have big controls there)
    if (currentScreen == "TalkDetail" || isInAutoMode) {
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
                    .height(56.dp) // Standard height for phone UI
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = talk.imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
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
                    // Standard size controls for phone mode
                    
                    // Previous track
                    IconButton(onClick = { viewModel.skipToPreviousTrack() }) {
                        Icon(Icons.Default.SkipPrevious, "Previous Track")
                    }
                    
                    // Play/Pause button
                    FilledIconButton(
                        onClick = { viewModel.togglePlayPause() },
                        modifier = Modifier.size(42.dp),
                        shape = CircleShape
                    ) {
                        Icon(
                            if (playbackState.isPlaying) Icons.Default.Pause 
                            else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause"
                        )
                    }
                    
                    // Next track
                    IconButton(onClick = { viewModel.skipToNextTrack() }) {
                        Icon(Icons.Default.SkipNext, "Next Track")
                    }
                }
            }
        }
    }
} 