package space.coljac.FreeAudio.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import space.coljac.FreeAudio.data.SearchState
import space.coljac.FreeAudio.data.Talk
import space.coljac.FreeAudio.ui.components.SearchBar
import space.coljac.FreeAudio.ui.components.TalkItem
import space.coljac.FreeAudio.viewmodel.AudioViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: AudioViewModel,
    onTalkSelected: (Talk) -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Free Buddhist Audio") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SearchBar(onSearch = { query ->
                if (query.length >= 3) {
                    viewModel.search(query)
                }
            })

            val searchState by viewModel.searchState.collectAsState()
            
            when (searchState) {
                is SearchState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is SearchState.Success -> {
                    val results = (searchState as SearchState.Success).response
                    LazyColumn {
                        items(
                            items = results.results,
                            key = { "${it.id}_search" }
                        ) { talk ->
                            TalkItem(
                                talk = talk,
                                onPlayClick = { 
                                    viewModel.playTalk(it)
                                    onTalkSelected(it)
                                }
                            )
                        }
                    }
                }
                is SearchState.Error -> {
                    Text(
                        text = "Error: ${(searchState as SearchState.Error).message}",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                SearchState.Empty -> {
                    // Show nothing or initial state
                }
            }
        }
    }
} 