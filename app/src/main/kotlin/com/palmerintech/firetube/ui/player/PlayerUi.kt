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
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import kotlin.math.abs
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
import androidx.compose.material.icons.filled.Lyrics
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.luminance
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

/**
 * The bar above the bottom navigation. Drag it up and Now Playing follows the finger out of it
 * ([onDrag] / [onDragEnd] feed the shared [PlayerSheetState]); a tap opens it too. Swipe left for
 * the next track and right for the previous one (the bar slides along and springs back when the
 * queue has nothing in that direction).
 */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** Vertical drag, in px (negative = up). */
    onDrag: (Float) -> Unit = {},
    /** Vertical drag released, with its velocity in px/s (negative = up). */
    onDragEnd: (Float) -> Unit = {},
    accent: Color? = null,
) {
    val track = state.current ?: return
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }
    val flingSpeed = with(LocalDensity.current) { 800.dp.toPx() }
    // The gesture outlives recompositions; read the latest state and callbacks.
    val latest by rememberUpdatedState(state)
    val next by rememberUpdatedState(onNext)
    val previous by rememberUpdatedState(onPrevious)
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)

    val swipe = Modifier.pointerInput(Unit) {
        val tracker = VelocityTracker()
        var totalX = 0f
        var totalY = 0f
        var axis: Orientation? = null
        detectDragGestures(
            onDragStart = { totalX = 0f; totalY = 0f; axis = null; tracker.resetTracking() },
            onDragCancel = {
                if (axis == Orientation.Vertical) dragEnd(0f)
                scope.launch { offsetX.animateTo(0f) }
            },
            onDragEnd = {
                val velocity = tracker.calculateVelocity()
                when (axis) {
                    Orientation.Vertical -> dragEnd(velocity.y)
                    Orientation.Horizontal -> scope.launch {
                        val x = offsetX.value
                        val far = width * 0.3f
                        val toNext = x < 0 && (x < -far || velocity.x < -flingSpeed) && latest.hasNext
                        val toPrevious = x > 0 && (x > far || velocity.x > flingSpeed) && latest.hasPrevious
                        if (toNext || toPrevious) {
                            val out = if (toNext) -width.toFloat() else width.toFloat()
                            offsetX.animateTo(out)
                            if (toNext) next() else previous()
                            offsetX.snapTo(-out * 0.4f) // the new track slides in from the other side
                        }
                        offsetX.animateTo(0f)
                    }
                    null -> Unit
                }
            },
        ) { change, amount ->
            change.consume()
            tracker.addPosition(change.uptimeMillis, change.position)
            totalX += amount.x
            totalY += amount.y
            if (axis == null) axis = if (abs(totalX) > abs(totalY)) Orientation.Horizontal else Orientation.Vertical
            when (axis) {
                Orientation.Horizontal -> scope.launch { offsetX.snapTo(offsetX.value + amount.x) }
                else -> drag(amount.y)
            }
        }
    }

    MiniPlayerCard(
        state, onTogglePlay, onNext, accent,
        modifier.onSizeChanged { width = it.width.coerceAtLeast(1) }
            // Outside the graphicsLayer: pointer positions must not move with the bar, or the
            // velocity tracker sees ~0 and flicks never skip.
            .then(swipe)
            .graphicsLayer {
                translationX = offsetX.value
                alpha = 1f - (abs(offsetX.value) / width).coerceAtMost(1f) * 0.6f
            }
            .clip(RoundedCornerShape(14.dp))
            .semantics {
                customActions = buildList {
                    if (state.hasPrevious) add(CustomAccessibilityAction("Previous track") { onPrevious(); true })
                    if (state.hasNext) add(CustomAccessibilityAction("Next track") { onNext(); true })
                }
            }
            .clickable(onClick = onOpen),
    )
}

/** The mini player's look, without its gestures; Now Playing also shows it while being dragged. */
@Composable
private fun MiniPlayerCard(
    state: PlayerUiState,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    accent: Color?,
    modifier: Modifier = Modifier,
) {
    val track = state.current ?: return
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).then(modifier),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 3.dp,
    ) {
        Column(
            Modifier.background(
                Brush.horizontalGradient(listOf((accent ?: MaterialTheme.colorScheme.primary).copy(alpha = if (accent != null) 0.45f else 0.22f), Color.Transparent)),
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
    sheet: PlayerSheetState,
    onClose: () -> Unit,
    accent: Color? = null,
) {
    // Keep D-pad focus inside while open; released as soon as closing starts so focus can go back.
    val trapFocus = sheet.expanded
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
    var showLyrics by remember { mutableStateOf(false) }
    var addToPlaylist by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }

    BackHandler(enabled = sheet.expanded, onBack = onClose)

    // Drag the whole screen down (or back up) and it follows the finger; see PlayerSheetState.
    val flingSpeed = with(LocalDensity.current) { 1000.dp.toPx() }
    val sheetCorner = with(LocalDensity.current) { 20.dp.toPx() }
    // While nearly collapsed, the top of the screen shows the mini player it's coming out of; the
    // controls only exist once they start fading in, so nothing invisible can be tapped.
    val nearlyCollapsed by remember { derivedStateOf { sheet.progress < CONTROLS_FROM } }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val wash = accent ?: MaterialTheme.colorScheme.primary

    val playFocus = remember { FocusRequester() }
    // D-pad users land on Play when the player opens (a no-op in touch mode).
    LaunchedEffect(trapFocus, nearlyCollapsed) { if (trapFocus && !nearlyCollapsed) runCatching { playFocus.requestFocus() } }
    Box(
        Modifier.fillMaxSize()
            .playerSheetDrag(sheet, flingSpeed)
            // After draggable: pointer positions must not move with the screen, or drags stall.
            .graphicsLayer {
                val open = sheet.progress
                translationY = (1f - open) * sheet.distance
                // Rounded like the mini player while it's being dragged, square once open.
                shape = RoundedCornerShape(topStart = sheetCorner * (1f - open), topEnd = sheetCorner * (1f - open))
                clip = open < 1f
            }
            .background(MaterialTheme.colorScheme.surface)
            .focusProperties { onExit = { if (trapFocus) cancelFocusChange() } }
            .focusGroup(),
    ) {
        // Not inside a Surface, so text and icons would otherwise default to black (unreadable in dark mode).
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            // Blurred artwork behind everything, washed with the artwork's own color. In dark mode a
            // scrim at the top and a fade into the surface below keep the bars and text readable.
            AsyncImage(
                model = track.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(60.dp).alpha(if (dark) 0.55f else 0.45f),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        *if (dark) {
                            listOf(0f to Color.Black.copy(alpha = 0.35f), 0.25f to wash.copy(alpha = 0.35f), 0.6f to MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), 1f to MaterialTheme.colorScheme.surface)
                        } else {
                            listOf(0f to wash.copy(alpha = 0.30f), 0.5f to Color.Transparent, 1f to MaterialTheme.colorScheme.surface)
                        }.toTypedArray(),
                    ),
                ),
            )

            if (!nearlyCollapsed) Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)
                    // Fades in once the mini player shown at the top has faded out, so the two never overlap.
                    .graphicsLayer { alpha = ((sheet.progress - CONTROLS_FROM) / 0.3f).coerceIn(0f, 1f) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close player") }
                    Text("Now playing", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
                        IconButton(onClick = { showLyrics = true }) { Icon(Icons.Default.Lyrics, "Lyrics") }
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
            if (nearlyCollapsed) {
                // Same place and width as the real mini player, which on wide screens sits beside the
                // navigation rail rather than spanning the screen. The card adds 8dp either side.
                // miniLeft is measured from the left, so place it absolutely (not mirrored in RTL).
                val density = LocalDensity.current
                val cardPad = with(density) { 8.dp.toPx() }
                val placed = if (sheet.miniWidth > 0f) {
                    Modifier.align(AbsoluteAlignment.TopLeft)
                        .absoluteOffset { IntOffset((sheet.miniLeft - cardPad).roundToInt(), 0) }
                        .width(with(density) { (sheet.miniWidth + 2 * cardPad).toDp() })
                } else {
                    Modifier.fillMaxWidth()
                }
                Box(placed) {
                    MiniPlayerCard(
                        state, player::togglePlay, player::next, accent,
                        Modifier.graphicsLayer { alpha = 1f - ((sheet.progress - 0.1f) / (CONTROLS_FROM - 0.1f)).coerceIn(0f, 1f) },
                    )
                }
            }
        }
    }

    if (showQueue) QueueSheet(container, state) { showQueue = false }
    if (showSleep) SleepSheet(container.sleepTimer, sleep) { showSleep = false }
    if (showLyrics) LyricsSheet(container, state) { showLyrics = false }
    if (addToPlaylist) AddToPlaylistDialog(container, listOf(track)) { addToPlaylist = false }
}

/** Sheet progress at which Now Playing's controls replace the mini player shown at its top. */
private const val CONTROLS_FROM = 0.25f

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
