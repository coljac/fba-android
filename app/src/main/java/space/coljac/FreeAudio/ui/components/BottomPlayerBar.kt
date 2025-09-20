package space.coljac.FreeAudio.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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
import kotlin.math.max

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
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Make artwork + titles navigable to the talk page
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onTalkClick(talk) },
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
                                .padding(horizontal = 8.dp)
                                .weight(1f)
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

                // Scrubber
                val position = playbackState.position.coerceAtLeast(0)
                val duration = max(0L, playbackState.duration)
                if (duration > 0L) {
                    Slider(
                        value = position.toFloat().coerceIn(0f, duration.toFloat()),
                        onValueChange = { newVal ->
                            // Update locally to feel responsive
                            viewModel.seekTo(newVal.toLong())
                        },
                        valueRange = 0f..duration.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
