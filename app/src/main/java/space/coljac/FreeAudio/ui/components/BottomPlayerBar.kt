package space.coljac.FreeAudio.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import space.coljac.FreeAudio.data.Talk
import space.coljac.FreeAudio.viewmodel.AudioViewModel

@Composable
fun BottomPlayerBar(
    viewModel: AudioViewModel,
    onTalkClick: (Talk) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentTalk by viewModel.currentTalk.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()

    currentTalk?.let { talk ->
        Surface(
            modifier = modifier,
            tonalElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
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
                    IconButton(onClick = { viewModel.skipToPreviousTrack() }) {
                        Icon(Icons.Default.SkipPrevious, "Previous")
                    }
                    IconButton(onClick = { viewModel.togglePlayPause() }) {
                        Icon(
                            if (playbackState.isPlaying) Icons.Default.Pause 
                            else Icons.Default.PlayArrow,
                            "Play/Pause"
                        )
                    }
                    IconButton(onClick = { viewModel.skipToNextTrack() }) {
                        Icon(Icons.Default.SkipNext, "Next")
                    }
                }
            }
        }
    }
} 