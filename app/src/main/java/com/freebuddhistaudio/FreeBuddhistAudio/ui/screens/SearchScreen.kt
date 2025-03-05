package com.freebuddhistaudio.FreeBuddhistAudio.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.freebuddhistaudio.FreeBuddhistAudio.data.SearchState
import com.freebuddhistaudio.FreeBuddhistAudio.data.Talk
import com.freebuddhistaudio.FreeBuddhistAudio.ui.components.SearchBar
import com.freebuddhistaudio.FreeBuddhistAudio.ui.components.TalkItem
import com.freebuddhistaudio.FreeBuddhistAudio.viewmodel.AudioViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: AudioViewModel,
    onTalkSelected: (Talk) -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Search") }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
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
                    LazyColumn(
                        modifier = Modifier.fillMaxSize() // Ensure the list fills all available space
                    ) {
                        item {
                            if (viewModel.isUpdatingSearchResults.collectAsState().value) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .padding(end = 8.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = "Updating track information...",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                        
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
} 