package com.freebuddhistaudio.FreeBuddhistAudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import com.freebuddhistaudio.FreeBuddhistAudio.data.Talk
import com.freebuddhistaudio.FreeBuddhistAudio.ui.components.TalkItem
import com.freebuddhistaudio.FreeBuddhistAudio.viewmodel.AudioViewModel
import android.util.Log

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: AudioViewModel,
    onNavigateToSearch: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onTalkSelected: (Talk) -> Unit
) {
    val recentPlays by viewModel.recentPlays.collectAsState()
    val currentTalk by viewModel.currentTalk.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val isInAutoMode by viewModel.isInAutoMode.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Free Buddhist Audio") },
                actions = {
                    IconButton(onClick = onNavigateToSearch) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // Add pull-to-refresh functionality using SwipeRefresh
            var isRefreshing by remember { mutableStateOf(false) }
            
            // Create a coroutine scope that follows the lifecycle of this composable
            val scope = rememberCoroutineScope()
            
            // Hidden debug shortcut (triple tap on the top right corner)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(48.dp)
                    .padding(top = 8.dp, end = 8.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { viewModel.toggleAutoMode() }
            )
            
            SwipeRefresh(
                state = rememberSwipeRefreshState(isRefreshing),
                onRefresh = {
                    // Refresh the recent plays list
                    viewModel.refreshRecentPlays()
                    // Use a coroutine to update the refresh state after a delay
                    scope.launch {
                        isRefreshing = true
                        kotlinx.coroutines.delay(800)
                        isRefreshing = false
                    }
                }
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    // Add player controls based on mode
                    if (currentTalk != null) {
                        item {
                            // Make sure isInAutoMode is working correctly
                            Log.d("HomeScreen", "isInAutoMode: $isInAutoMode")
                            
                            // Prominent player controls only in Auto mode
                            if (isInAutoMode) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    ),
                                    elevation = CardDefaults.cardElevation(
                                        defaultElevation = 4.dp
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        // Track info
                                        currentTalk?.let { talk ->
                                            Text(
                                                text = talk.title,
                                                style = MaterialTheme.typography.titleMedium,
                                                modifier = Modifier.padding(bottom = 8.dp)
                                            )
                                            
                                            playbackState.currentTrack?.let { track ->
                                                Text(
                                                    text = track.title.ifEmpty { "Track ${playbackState.currentTrackIndex + 1}" },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    modifier = Modifier.padding(bottom = 16.dp)
                                                )
                                            }
                                        }
                                        
                                        // Main controls row - only large in Auto mode
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
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
                            } else {
                                // No player controls in phone UI - we have the bottom bar instead
                                // Just show a spacer to maintain consistent layout
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
            
                    if (recentPlays.isNotEmpty()) {
                        item {
                            Text(
                                "Recently Played",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                        
                        items(
                            items = recentPlays,
                            key = { "${it.id}_recent" }
                        ) { talk ->
                            TalkItem(
                                talk = talk,
                                onPlayClick = { 
                                    viewModel.playTalk(it)
                                    onTalkSelected(it)
                                }
                            )
                        }
                    } else {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Search and play talks to see recent items",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DismissBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Icon(
            Icons.Default.Delete,
            contentDescription = "Delete",
            tint = MaterialTheme.colorScheme.onErrorContainer
        )
    }
} 