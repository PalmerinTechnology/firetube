package com.palmerintech.firetube.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.palmerintech.firetube.extractor.Chapter
import com.palmerintech.firetube.extractor.Chapters
import com.palmerintech.firetube.ui.components.focusRing
import com.palmerintech.firetube.ui.components.formatDuration
import kotlin.math.abs

/** The current track's chapters, the playing one highlighted. Tap (or D-pad select) one to jump there. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChaptersSheet(
    title: String,
    chapters: List<Chapter>,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = Chapters.indexAt(chapters, positionMs)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 1).coerceAtLeast(0))
    val currentFocus = remember { FocusRequester() }
    val shape = RoundedCornerShape(12.dp)
    // D-pad users start on the playing chapter (a no-op in touch mode).
    LaunchedEffect(Unit) { runCatching { currentFocus.requestFocus() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.9f).navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Chapters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    title, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(chapters) { index, chapter ->
                    val isCurrent = index == current
                    val color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    val start = formatDuration(chapter.startMs / 1000).ifEmpty { "0:00" }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                            .then(if (isCurrent) Modifier.focusRequester(currentFocus) else Modifier)
                            .focusRing(shape)
                            .clip(shape)
                            .clickable(onClickLabel = "Jump to this chapter") { onSeek(chapter.startMs) }
                            .semantics { selected = isCurrent }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            start, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = 56.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            chapter.title, style = MaterialTheme.typography.bodyLarge, color = color,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Small dots on a seek bar's track where chapters start (not the first, at the very start), in
 * the slider's own tick colors. None within [thumbGap] of the thumb, where the track has a gap.
 */
internal fun Modifier.chapterTicks(
    chapters: List<Chapter>,
    durationMs: Long,
    value: () -> Float,
    active: Color,
    inactive: Color,
    thumbGap: Dp = 6.dp,
): Modifier = if (chapters.size < 2 || durationMs <= 0) this else drawWithContent {
    drawContent()
    val at = value()
    val gap = thumbGap.toPx()
    val radius = 1.5.dp.toPx()
    for (chapter in chapters) {
        val fraction = chapter.startMs.toFloat() / durationMs
        if (fraction <= 0f || fraction >= 1f) continue
        val x = fraction * size.width
        if (abs(x - at * size.width) < gap) continue
        drawCircle(if (fraction <= at) active else inactive, radius, Offset(x, size.height / 2))
    }
}
