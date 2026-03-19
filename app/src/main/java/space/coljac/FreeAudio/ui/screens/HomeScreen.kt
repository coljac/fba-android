package space.coljac.FreeAudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import space.coljac.FreeAudio.data.Talk
import space.coljac.FreeAudio.ui.components.TalkItem
import space.coljac.FreeAudio.viewmodel.AudioViewModel

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
        var isRefreshing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val pullToRefreshState = rememberPullToRefreshState()

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                viewModel.refreshRecentPlays()
                scope.launch {
                    isRefreshing = true
                    delay(800)
                    isRefreshing = false
                }
            },
            state = pullToRefreshState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
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
                            onClick = { onTalkSelected(it) },
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
