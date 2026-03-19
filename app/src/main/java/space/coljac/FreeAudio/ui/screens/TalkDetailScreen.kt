package space.coljac.FreeAudio.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBarsPadding
import coil.compose.AsyncImage
import space.coljac.FreeAudio.viewmodel.AudioViewModel
import space.coljac.FreeAudio.data.Talk
import space.coljac.FreeAudio.data.Track
import space.coljac.FreeAudio.data.SeriesMember
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TalkDetailScreen(
    viewModel: AudioViewModel,
    talkId: String,
    onNavigateUp: () -> Unit,
    onNavigateToTalk: (String) -> Unit = {}
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }

    LaunchedEffect(talkId) {
        android.util.Log.d("TalkDetailScreen", "Loading talk with ID: $talkId")
        viewModel.loadTalk(talkId)
    }

    val playbackState by viewModel.playbackState.collectAsState()
    val currentTalk by viewModel.currentTalk.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val isDownloaded by viewModel.isDownloaded.collectAsState()
    val downloadError by viewModel.downloadError.collectAsState()
    val playbackError by viewModel.playbackError.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
    val sleepTimerRemainingMs by viewModel.sleepTimerRemainingMs.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Talk Details") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (isDownloaded) {
                        IconButton(onClick = { showDeleteConfirmation = true }) {
                            Icon(Icons.Default.Delete, "Delete Download")
                        }
                    }
                }
            )
        },
        bottomBar = {
            // Respect system navigation bar insets so controls aren't obscured
            Column(modifier = Modifier.navigationBarsPadding()) {
                // Only show download button for non-series talks
                if (currentTalk?.isSeries != true) {
                    // Download/Favorite Buttons (fixed)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                currentTalk?.let { talk ->
                                    if (isDownloaded) {
                                        showDeleteConfirmation = true
                                    } else if (downloadProgress == null) {
                                        viewModel.downloadTalk(talk)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = downloadProgress == null,
                            colors = if (isDownloaded) {
                                ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            }
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                when {
                                    downloadProgress != null -> {
                                        LinearProgressIndicator(
                                            progress = { downloadProgress ?: 0f },
                                            modifier = Modifier
                                                .width(24.dp)
                                                .height(2.dp)
                                        )
                                        Text(
                                            "Downloading ${(downloadProgress?.times(100))?.toInt()}%",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    isDownloaded -> {
                                        Icon(
                                            Icons.Default.Delete,
                                            "Remove Download",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text("Remove")
                                    }
                                    else -> {
                                        Icon(
                                            Icons.Default.Download,
                                            "Download",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text("Download")
                                    }
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = { currentTalk?.let { viewModel.toggleFavorite(it) } },
                            modifier = Modifier.weight(1f),
                            colors = if (currentTalk?.isFavorite == true) {
                                ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            }
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (currentTalk?.isFavorite == true)
                                        Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = if (currentTalk?.isFavorite == true)
                                        "Remove from Favourites" else "Add to Favourites",
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(if (currentTalk?.isFavorite == true) "Favourited" else "Favourite")
                            }
                        }
                    }

                    // Speed & Sleep Timer controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showSpeedDialog = true }) {
                            Icon(
                                Icons.Default.Speed,
                                contentDescription = "Playback Speed",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (playbackSpeed == 1.0f) "1x" else "${playbackSpeed}x",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        TextButton(onClick = { showSleepDialog = true }) {
                            Icon(
                                Icons.Default.Bedtime,
                                contentDescription = "Sleep Timer",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = when {
                                    sleepTimerRemainingMs == -1L -> "End of track"
                                    sleepTimerRemainingMs > 0 -> {
                                        val mins = sleepTimerRemainingMs / 60_000
                                        val secs = (sleepTimerRemainingMs % 60_000) / 1000
                                        "${mins}:${secs.toString().padStart(2, '0')}"
                                    }
                                    else -> "Sleep"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    // Player Controls (fixed) - only for non-series
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { viewModel.skipToPreviousTrack() }) {
                                Icon(Icons.Default.SkipPrevious, "Previous Track")
                            }
                            IconButton(onClick = { viewModel.seekBackward() }) {
                                Icon(Icons.Default.Replay10, "Skip Back 10 Seconds")
                            }
                            FilledIconButton(
                                onClick = { viewModel.playOrToggleCurrentTalk() }
                            ) {
                                Icon(
                                    if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    "Play/Pause"
                                )
                            }
                            IconButton(onClick = { viewModel.seekForward() }) {
                                Icon(Icons.Default.Forward10, "Skip Forward 10 Seconds")
                            }
                            IconButton(onClick = { viewModel.skipToNextTrack() }) {
                                Icon(Icons.Default.SkipNext, "Next Track")
                            }
                        }

                        // Scrubber: always visible; disabled until duration is known
                        val position = playbackState.position.coerceAtLeast(0)
                        val duration = max(0L, playbackState.duration)
                        val sliderMax = max(1f, duration.toFloat())
                        Slider(
                            value = position.toFloat().coerceIn(0f, sliderMax),
                            onValueChange = { newVal -> viewModel.seekTo(newVal.toLong()) },
                            valueRange = 0f..sliderMax,
                            enabled = duration > 0L,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    // For series, only show favourite button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        OutlinedButton(
                            onClick = { currentTalk?.let { viewModel.toggleFavorite(it) } },
                            modifier = Modifier.fillMaxWidth(0.5f),
                            colors = if (currentTalk?.isFavorite == true) {
                                ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            }
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (currentTalk?.isFavorite == true)
                                        Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = if (currentTalk?.isFavorite == true)
                                        "Remove from Favourites" else "Add to Favourites",
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(if (currentTalk?.isFavorite == true) "Favourited" else "Favourite")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            currentTalk?.let { talk ->
                // Talk Info & Description
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = talk.imageUrl,
                        contentDescription = "Speaker",
                        modifier = Modifier
                            .size(80.dp)
                            .padding(end = 16.dp)
                    )
                    Column {
                        Text(
                            text = talk.title,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "${talk.speaker} (${talk.year})",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Description",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                val descriptionScrollState = rememberScrollState()
                Text(
                    text = talk.blurb,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 100.dp)
                        .verticalScroll(descriptionScrollState)
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (talk.isSeries) {
                    // Show series members
                    Text(
                        text = "Talks in this Series (${talk.seriesMembers.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Column {
                            talk.seriesMembers.forEachIndexed { index, member ->
                                SeriesMemberItem(
                                    member = member,
                                    memberIndex = index,
                                    onClick = {
                                        android.util.Log.d("TalkDetailScreen", "Series member clicked: ${member.id}")
                                        onNavigateToTalk(member.id)
                                    }
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                } else {
                    // Show tracks for regular talks
                    if (talk.tracks.isNotEmpty()) {
                        Text(
                            text = "Tracks (${talk.tracks.size})",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Column {
                            talk.tracks.forEachIndexed { index, track ->
                                val isCurrentTrack =
                                    playbackState.currentTrackIndex == index &&
                                    currentTalk?.id == talk.id
                                val trackProgress = if (isCurrentTrack && playbackState.duration > 0) {
                                    (playbackState.position.toFloat() / playbackState.duration.toFloat()).coerceIn(0f, 1f)
                                } else 0f
                                TrackItem(
                                    track = track,
                                    trackIndex = index,
                                    isPlaying = isCurrentTrack && playbackState.isPlaying,
                                    progress = trackProgress,
                                    onClick = {
                                        android.util.Log.d("TalkDetailScreen", "Track clicked index=$index talkId=${talk.id}")
                                        if (isCurrentTrack && playbackState.isPlaying) {
                                            viewModel.togglePlayPause()
                                        } else {
                                            viewModel.playTrack(index)
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // Show download error dialog
    downloadError?.let { errorMessage ->
        AlertDialog(
            onDismissRequest = { viewModel.clearDownloadError() },
            title = { Text("Download Failed") },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearDownloadError() }) {
                    Text("OK")
                }
            }
        )
    }

    // Show playback error dialog
    playbackError?.let { errorMessage ->
        AlertDialog(
            onDismissRequest = { viewModel.clearPlaybackError() },
            title = { Text("Playback Error") },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearPlaybackError() }) {
                    Text("OK")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Remove Download") },
            text = { Text("Are you sure you want to remove this downloaded talk? You'll need to download it again to listen offline.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        currentTalk?.let { talk ->
                            viewModel.deleteTalk(talk)
                        }
                        showDeleteConfirmation = false
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Speed selection dialog
    if (showSpeedDialog) {
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text("Playback Speed") },
            text = {
                Column {
                    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                    speeds.forEach { speed ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setPlaybackSpeed(speed)
                                    showSpeedDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = playbackSpeed == speed,
                                onClick = {
                                    viewModel.setPlaybackSpeed(speed)
                                    showSpeedDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("${speed}x")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Sleep timer dialog
    if (showSleepDialog) {
        AlertDialog(
            onDismissRequest = { showSleepDialog = false },
            title = { Text("Sleep Timer") },
            text = {
                Column {
                    val options = listOf(
                        "Off" to 0,
                        "15 minutes" to 15,
                        "30 minutes" to 30,
                        "45 minutes" to 45,
                        "60 minutes" to 60,
                    )
                    options.forEach { (label, minutes) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (minutes == 0) {
                                        viewModel.cancelSleepTimer()
                                    } else {
                                        viewModel.setSleepTimer(minutes)
                                    }
                                    showSleepDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = when {
                                    minutes == 0 -> sleepTimerRemainingMs == 0L
                                    else -> false // Can't easily match, so don't pre-select
                                },
                                onClick = {
                                    if (minutes == 0) {
                                        viewModel.cancelSleepTimer()
                                    } else {
                                        viewModel.setSleepTimer(minutes)
                                    }
                                    showSleepDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                    // End of track option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.setSleepTimerEndOfTrack()
                                showSleepDialog = false
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = sleepTimerRemainingMs == -1L,
                            onClick = {
                                viewModel.setSleepTimerEndOfTrack()
                                showSleepDialog = false
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("End of current track")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleepDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SeriesMemberItem(
    member: SeriesMember,
    memberIndex: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${memberIndex + 1}.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.width(32.dp)
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = member.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (member.blurb.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = member.blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play",
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun TrackItem(
    track: Track,
    trackIndex: Int,
    isPlaying: Boolean,
    progress: Float = 0f,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isPlaying) {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = "Currently Playing",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(24.dp)
                            .padding(end = 8.dp)
                    )
                } else {
                    Text(
                        text = "${trackIndex + 1}.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.width(32.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = track.title.ifEmpty { "Track ${trackIndex + 1}" },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isPlaying)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (track.duration.isNotEmpty()) track.duration else "Unknown duration",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isPlaying)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onClick) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play"
                    )
                }
            }
            if (isPlaying) {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .padding(horizontal = 8.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
