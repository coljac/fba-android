package com.freebuddhistaudio.FreeBuddhistAudio.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.freebuddhistaudio.FreeBuddhistAudio.data.Talk

@Composable
fun TalkItem(
    talk: Talk,
    onPlayClick: (Talk) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onPlayClick(talk) }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = talk.imageUrl,
                contentDescription = "Speaker image",
                modifier = Modifier
                    .size(60.dp)
                    .padding(end = 16.dp),
                contentScale = ContentScale.Crop
            )
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = talk.title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${talk.speaker} (${talk.year})",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${talk.tracks.size} track${if (talk.tracks.size != 1) "s" else ""} • ${talk.year}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    
                    if (talk.isFavorite) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = "Favorited",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            
            IconButton(onClick = { onPlayClick(talk) }) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play")
            }
        }
    }
} 