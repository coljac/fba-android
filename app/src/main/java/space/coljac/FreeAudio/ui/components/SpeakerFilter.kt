package space.coljac.FreeAudio.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp

@Composable
fun SpeakerFilter(
    availableSpeakers: List<String>,
    selectedSpeaker: String?,
    onSpeakerSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    if (availableSpeakers.isEmpty() && selectedSpeaker == null) return

    var expanded by remember { mutableStateOf(false) }
    var filterText by remember { mutableStateOf("") }

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "chevron"
    )

    val filtered = remember(availableSpeakers, filterText) {
        if (filterText.isBlank()) availableSpeakers
        else availableSpeakers.filter { it.contains(filterText, ignoreCase = true) }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "Collapse speaker filter" else "Expand speaker filter",
                modifier = Modifier.rotate(chevronRotation)
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Speaker",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            if (selectedSpeaker != null) {
                AssistChip(
                    onClick = { onSpeakerSelected(null) },
                    label = { Text(selectedSpeaker) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear filter",
                            modifier = Modifier.size(AssistChipDefaults.IconSize)
                        )
                    }
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                OutlinedTextField(
                    value = filterText,
                    onValueChange = { filterText = it },
                    placeholder = { Text("Filter speakers…") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                ) {
                    if (selectedSpeaker != null) {
                        item(key = "__clear__") {
                            ListItem(
                                headlineContent = { Text("All speakers") },
                                modifier = Modifier.clickable {
                                    onSpeakerSelected(null)
                                    expanded = false
                                    filterText = ""
                                }
                            )
                        }
                    }

                    if (filtered.isEmpty()) {
                        item(key = "__empty__") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No speakers match",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        items(filtered, key = { it }) { speaker ->
                            val isSelected = speaker == selectedSpeaker
                            ListItem(
                                headlineContent = { Text(speaker) },
                                modifier = Modifier.clickable {
                                    onSpeakerSelected(if (isSelected) null else speaker)
                                    expanded = false
                                    filterText = ""
                                },
                                colors = if (isSelected) {
                                    ListItemDefaults.colors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                                    )
                                } else {
                                    ListItemDefaults.colors()
                                }
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider()
    }
}
