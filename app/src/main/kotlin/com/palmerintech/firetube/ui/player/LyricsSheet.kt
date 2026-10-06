package com.palmerintech.firetube.ui.player

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.palmerintech.firetube.AppContainer
import com.palmerintech.firetube.R
import com.palmerintech.firetube.lyrics.Lrc
import com.palmerintech.firetube.lyrics.LrcLine
import com.palmerintech.firetube.lyrics.LyricsResult
import com.palmerintech.firetube.player.PlayerUiState
import com.palmerintech.firetube.ui.Load
import com.palmerintech.firetube.ui.artistLabel
import com.palmerintech.firetube.ui.message
import com.palmerintech.firetube.ui.components.focusRing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.net.ConnectException
import java.net.UnknownHostException
import kotlin.math.roundToLong

/** Lyrics for the current track, following playback when they're synced. */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsSheet(container: AppContainer, state: PlayerUiState, onDismiss: () -> Unit) {
    val track = state.current ?: return
    val lyrics = container.lyrics
    var attempt by remember { mutableIntStateOf(0) }
    // Keyed on the track, so skipping to the next song while the sheet is open looks that one up.
    val load by produceState<Load<LyricsResult>>(Load.Loading, track.id, attempt) {
        value = lyrics.cached(track.id)?.let { Load.Ready(it) } ?: Load.Loading
        value = try {
            Load.Ready(lyrics.lookup(track))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(if (e is UnknownHostException || e is ConnectException) R.string.error_offline else R.string.lyrics_error)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.9f).navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.lyrics_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${track.title} · ${artistLabel(track)}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            LyricsBody(load, rememberPlaybackClock(state), container.player::seekTo, { attempt++ }, Modifier.weight(1f))
            Text(
                stringResource(R.string.lyrics_source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The playback position, read as a function so only what uses it recomposes. The player reports
 * it twice a second; in between it's estimated from the time since, so a line lights up when it
 * starts rather than up to half a second late, at the current playback speed. Each report
 * corrects the estimate.
 */
@Composable
private fun rememberPlaybackClock(state: PlayerUiState): () -> Long {
    val playing = state.isPlaying && !state.isBuffering
    val reportedAt = remember(state.positionMs, playing) { SystemClock.uptimeMillis() }
    val reported by rememberUpdatedState(state.positionMs)
    val anchor by rememberUpdatedState(reportedAt)
    val running by rememberUpdatedState(playing)
    val speed by rememberUpdatedState(state.speed)
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(playing) {
        while (playing) {
            now = SystemClock.uptimeMillis()
            delay(CLOCK_STEP_MS)
        }
    }
    return remember { { estimatePosition(reported, anchor, now, running, speed) } }
}

/**
 * [reportedMs] plus the time since it was reported at [reportedAt], while playing, at [speed]
 * (song time runs twice as fast at 2x). The elapsed time is capped, in case reports stop.
 */
internal fun estimatePosition(reportedMs: Long, reportedAt: Long, now: Long, playing: Boolean, speed: Float = 1f): Long {
    if (!playing) return reportedMs
    val elapsed = (now - reportedAt).coerceIn(0, MAX_ESTIMATE_MS)
    return reportedMs + (elapsed * speed.coerceAtLeast(0f)).roundToLong()
}

/** The sheet's content for each lookup state; separate from [LyricsSheet] so it can be tested. */
@Composable
internal fun LyricsBody(
    load: Load<LyricsResult>,
    positionMs: () -> Long,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth()) {
        when (load) {
            Load.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is Load.Failed -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Message(load.message)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_try_again)) }
            }
            is Load.Ready -> when (val result = load.value) {
                is LyricsResult.Synced -> SyncedLyrics(result.lines, positionMs, onSeek)
                is LyricsResult.Plain -> PlainLyrics(result.text)
                LyricsResult.Instrumental -> Message(stringResource(R.string.lyrics_instrumental), Modifier.align(Alignment.Center))
                LyricsResult.NotFound -> Message(stringResource(R.string.lyrics_not_found), Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun Message(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = modifier.padding(24.dp),
    )
}

/**
 * Timed lines with the current one highlighted and kept centered. Tap (or D-pad select) a line to
 * jump there. Scrolling by hand, or moving D-pad focus, pauses the following for a few seconds.
 */
@Composable
private fun SyncedLyrics(lines: List<LrcLine>, positionMs: () -> Long, onSeek: (Long) -> Unit) {
    val position by rememberUpdatedState(positionMs)
    // Recomposes when the line changes, not on every clock step.
    val current by remember(lines) { derivedStateOf { Lrc.indexAt(lines, position()) } }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = current.coerceAtLeast(0))
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var heldUntil by remember { mutableLongStateOf(0L) }
    var focused by remember { mutableIntStateOf(-1) }
    val currentFocus = remember { FocusRequester() }
    val shape = RoundedCornerShape(12.dp)

    LaunchedEffect(dragged) {
        if (dragged) heldUntil = Long.MAX_VALUE
        else if (heldUntil == Long.MAX_VALUE) heldUntil = SystemClock.uptimeMillis() + HOLD_MS
    }
    LaunchedEffect(current) {
        if (current < 0 || dragged || SystemClock.uptimeMillis() < heldUntil) return@LaunchedEffect
        // The list is padded by half its height, so an item at the top of the content sits mid-screen.
        val size = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }?.size ?: 0
        listState.animateScrollToItem(current, size / 2)
        // A D-pad user's focus follows along, rather than being left on a line scrolled out of view.
        if (focused >= 0) runCatching { currentFocus.requestFocus() }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = maxHeight / 2),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(lines) { index, line ->
                val isCurrent = index == current
                val color by animateColorAsState(
                    when {
                        isCurrent -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (index < current) 0.5f else 0.8f)
                    },
                    label = "lyric",
                )
                Text(
                    line.text.ifEmpty { "♪" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    color = color,
                    modifier = Modifier.fillMaxWidth()
                        .then(if (isCurrent) Modifier.focusRequester(currentFocus) else Modifier)
                        .onFocusChanged {
                            if (it.isFocused) {
                                // Moved there by the user (not by following playback): stop following for a bit.
                                if (index != current) heldUntil = SystemClock.uptimeMillis() + HOLD_MS
                                focused = index
                            } else if (focused == index) {
                                focused = -1
                            }
                        }
                        .focusRing(shape)
                        .clip(shape)
                        // Follow again from the tapped line once playback gets there.
                        .clickable(onClickLabel = stringResource(R.string.lyrics_jump_to_line)) { heldUntil = 0; onSeek(line.timeMs) }
                        .semantics { selected = isCurrent }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** Untimed lyrics; each line can take D-pad focus so the list scrolls on TV. */
@Composable
private fun PlainLyrics(text: String) {
    val shape = RoundedCornerShape(8.dp)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp)) {
        itemsIndexed(text.trim().lines()) { _, line ->
            if (line.isBlank()) {
                Spacer(Modifier.height(16.dp))
            } else {
                Text(
                    line.trim(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().focusRing(shape).focusable().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** How long hand scrolling or D-pad browsing pauses following playback. */
private const val HOLD_MS = 4000L

/** How often the estimated position advances between the player's reports. */
private const val CLOCK_STEP_MS = 50L

/** Never estimate further than this (wall time) past a report; the player reports at least every 500 ms. */
private const val MAX_ESTIMATE_MS = 750L
