package space.coljac.FreeAudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import space.coljac.FreeAudio.data.Talk
import space.coljac.FreeAudio.ui.components.TalkItem
import space.coljac.FreeAudio.viewmodel.AudioViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: AudioViewModel,
    onNavigateToSearch: () -> Unit,
    onTalkSelected: (Talk) -> Unit
) {
    val downloadedTalks by viewModel.downloadedTalks.collectAsState()
    val recentPlays by viewModel.recentPlays.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Free Buddhist Audio") }
            )
        },
        floatingActionButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FloatingActionButton(
                    onClick = { viewModel.refreshDownloads() }
                ) {
                    Icon(Icons.Default.Refresh, "Refresh Downloads")
                }
                FloatingActionButton(
                    onClick = onNavigateToSearch
                ) {
                    Icon(Icons.Default.Search, "Search")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (downloadedTalks.isNotEmpty()) {
                item {
                    Text(
                        "Downloaded Talks",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                
                items(
                    items = downloadedTalks,
                    key = { it.id }
                ) { talk ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { dismissValue ->
                            if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
                                viewModel.deleteTalk(talk)
                                true
                            } else false
                        }
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromEndToStart = true,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = { DismissBackground() }
                    ) {
                        TalkItem(
                            talk = talk,
                            onPlayClick = { onTalkSelected(it) }
                        )
                    }
                }
            }

            if (recentPlays.isNotEmpty()) {
                item {
                    Text(
                        "Recently Played",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                
                items(
                    items = recentPlays,
                    key = { it.id }
                ) { talk ->
                    TalkItem(
                        talk = talk,
                        onPlayClick = { onTalkSelected(it) }
                    )
                }
            }

            if (downloadedTalks.isEmpty() && recentPlays.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Search and download talks to get started",
                            style = MaterialTheme.typography.bodyLarge
                        )
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