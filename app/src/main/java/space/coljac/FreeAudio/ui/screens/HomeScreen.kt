package com.freebuddhistaudio.FreeBuddhistAudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import com.freebuddhistaudio.FreeBuddhistAudio.data.Talk
import com.freebuddhistaudio.FreeBuddhistAudio.ui.components.TalkItem
import com.freebuddhistaudio.FreeBuddhistAudio.viewmodel.AudioViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: AudioViewModel,
    onNavigateToSearch: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onTalkSelected: (Talk) -> Unit
) {
    val recentPlays by viewModel.recentPlays.collectAsState()

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