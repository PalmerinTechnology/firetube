package com.palmerintech.firetube.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BedtimeOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.palmerintech.firetube.AppContainer
import com.palmerintech.firetube.player.PlayerUiState
import com.palmerintech.firetube.player.SleepTimer
import com.palmerintech.firetube.ui.components.AddToPlaylistDialog
import com.palmerintech.firetube.ui.components.Artwork
import com.palmerintech.firetube.ui.components.focusRing
import com.palmerintech.firetube.ui.theme.LocalFireBrushes
import com.palmerintech.firetube.ui.components.CastButton
import com.palmerintech.firetube.ui.components.LocalTrackMenu
import com.palmerintech.firetube.ui.components.TrackRow
import com.palmerintech.firetube.ui.components.formatDuration
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun MiniPlayer(state: PlayerUiState, onTogglePlay: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val track = state.current ?: return
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column(
            Modifier.background(
                Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), Color.Transparent)),
            ),
        ) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track.thumbnailUrl, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PlayPauseButton(state, onTogglePlay, small = true)
                IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next") }
            }
            val progress = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun PlayPauseButton(state: PlayerUiState, onClick: () -> Unit, small: Boolean, modifier: Modifier = Modifier) {
    if (small) {
        IconButton(onClick = onClick) {
            when {
                state.isBuffering && !state.isPlaying -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                state.isPlaying -> Icon(Icons.Default.Pause, "Pause")
                else -> Icon(Icons.Default.PlayArrow, "Play")
            }
        }
    } else {
        Box(
            modifier.size(76.dp).focusRing(CircleShape).clip(CircleShape).background(LocalFireBrushes.current.flame)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.isBuffering && !state.isPlaying -> CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp, color = Color.White)
                state.isPlaying -> Icon(Icons.Default.Pause, "Pause", Modifier.size(40.dp), tint = Color.White)
                else -> Icon(Icons.Default.PlayArrow, "Play", Modifier.size(40.dp), tint = Color.White)
            }
        }
    }
}

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    container: AppContainer,
    onClose: () -> Unit,
    /** Keep D-pad focus inside while open; released as soon as closing starts so focus can go back. */
    trapFocus: Boolean = true,
) {
    val state by container.player.state.collectAsState()
    val track = state.current
    if (track == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val player = container.player
    val isFavorite by container.library.isFavorite(track.id).collectAsState(false)
    val sleep by container.sleepTimer.state.collectAsState()
    val scope = rememberCoroutineScope()
    val menu = LocalTrackMenu.current
    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var addToPlaylist by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }

    BackHandler(onBack = onClose)

    val playFocus = remember { FocusRequester() }
    // D-pad users land on Play when the player opens (a no-op in touch mode).
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
            .focusProperties { onExit = { if (trapFocus) cancelFocusChange() } }
            .focusGroup(),
    ) {
        // Blurred artwork wash behind everything.
        AsyncImage(
            model = track.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(60.dp).alpha(0.45f),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.30f), Color.Transparent, MaterialTheme.colorScheme.surface),
                ),
            ),
        )

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close player") }
                Text("Now playing", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                CastButton(container.castAvailable)
                IconButton(onClick = { menu.open(track) }) { Icon(Icons.Default.MoreVert, "More") }
            }
            // Landscape (tablets, TV): artwork beside the controls instead of above them.
            val landscape = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
            val artwork = @Composable { modifier: Modifier ->
                Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(20.dp))) {
                    Artwork(track.thumbnailUrl, 1000.dp, Modifier.fillMaxSize(), corner = 20.dp)
                }
            }
            val controls = @Composable {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(track.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.basicMarquee())
                        Text(track.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    IconButton(onClick = { scope.launch { container.library.toggleFavorite(track) } }) {
                        Icon(
                            if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            if (isFavorite) "Remove from favorites" else "Add to favorites",
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                val duration = state.durationMs.coerceAtLeast(1)
                Slider(
                    value = scrubbing ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = { scrubbing = it },
                    onValueChangeFinished = { scrubbing?.let { player.seekTo((it * duration).toLong()) }; scrubbing = null },
                )
                Row {
                    val shown = scrubbing?.let { (it * duration).toLong() } ?: state.positionMs
                    Text(formatDuration(shown / 1000).ifEmpty { "0:00" }, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text(formatDuration(state.durationMs / 1000), style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = player::toggleShuffle) {
                        Icon(Icons.Default.Shuffle, "Shuffle", tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = player::previous, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(36.dp)) }
                    PlayPauseButton(state, player::togglePlay, small = false, modifier = Modifier.focusRequester(playFocus))
                    IconButton(onClick = player::next, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.SkipNext, "Next", Modifier.size(36.dp)) }
                    IconButton(onClick = player::cycleRepeat) {
                        Icon(
                            if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            "Repeat",
                            tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = { showSleep = true }) {
                        Icon(if (sleep == SleepTimer.State.Off) Icons.Default.BedtimeOff else Icons.Default.Bedtime, "Sleep timer",
                            tint = if (sleep == SleepTimer.State.Off) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { addToPlaylist = true }) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist") }
                    IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue") }
                }
            }
            if (landscape) {
                Row(
                    Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    artwork(Modifier.fillMaxHeight())
                    // Short landscape phones can't fit every control; let them scroll.
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { controls() }
                }
            } else {
                Spacer(Modifier.weight(1f))
                artwork(Modifier.fillMaxWidth())
                Spacer(Modifier.weight(1f))
                controls()
            }
        }
    }

    if (showQueue) QueueSheet(container, state) { showQueue = false }
    if (showSleep) SleepSheet(container.sleepTimer, sleep) { showSleep = false }
    if (addToPlaylist) AddToPlaylistDialog(container, listOf(track)) { addToPlaylist = false }
}

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(container: AppContainer, state: PlayerUiState, onDismiss: () -> Unit) {
    val player = container.player
    var items by remember(state.queue) { mutableStateOf(state.queue.mapIndexed { i, t -> "$i:${t.id}" to t }) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.currentIndex.coerceAtLeast(0))
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        items = items.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Up next", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyColumn(state = listState, modifier = Modifier.navigationBarsPadding()) {
            itemsIndexed(items, key = { _, it -> it.first }) { index, (key, track) ->
                ReorderableItem(reorder, key = key) {
                    TrackRow(
                        track,
                        onClick = { player.skipTo(index) },
                        onMore = null,
                        isCurrent = index == state.currentIndex,
                        trailing = {
                            if (index != state.currentIndex) {
                                IconButton(onClick = { player.removeQueueItem(index) }) { Icon(Icons.Default.Close, "Remove from queue") }
                            }
                            IconButton(
                                modifier = Modifier.draggableHandle(
                                    onDragStarted = { dragFrom = items.indexOfFirst { it.first == key } },
                                    onDragStopped = {
                                        val to = items.indexOfFirst { it.first == key }
                                        if (dragFrom >= 0 && to >= 0 && dragFrom != to) player.moveQueueItem(dragFrom, to)
                                        dragFrom = -1
                                    },
                                ),
                                onClick = {},
                            ) { Icon(Icons.Default.DragHandle, "Reorder") }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepSheet(timer: SleepTimer, state: SleepTimer.State, onDismiss: () -> Unit) {
    var custom by remember { mutableFloatStateOf(30f) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Sleep timer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            when (state) {
                is SleepTimer.State.At -> Text("Stops at ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(state.epochMillis))}")
                SleepTimer.State.EndOfTrack -> Text("Stops when this song ends")
                SleepTimer.State.Off -> {}
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60).forEach { m -> TextButton(onClick = { timer.start(m); onDismiss() }) { Text("$m min") } }
                TextButton(onClick = { timer.endOfTrack(); onDismiss() }) { Text("End of song") }
            }
            Text("Custom: ${custom.toInt()} min")
            Slider(custom, { custom = it }, valueRange = 5f..180f, steps = 34)
            Row {
                TextButton(onClick = { timer.start(custom.toInt()); onDismiss() }) { Text("Start") }
                Spacer(Modifier.weight(1f))
                if (state != SleepTimer.State.Off) TextButton(onClick = { timer.cancel(); onDismiss() }) { Text("Turn off") }
            }
        }
    }
}
