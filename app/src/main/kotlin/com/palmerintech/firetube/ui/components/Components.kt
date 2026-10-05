package com.palmerintech.firetube.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.palmerintech.firetube.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.palmerintech.firetube.ui.theme.LocalFireBrushes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.palmerintech.firetube.extractor.Track

/** Album-art style thumbnail with a music-note placeholder. YouTube thumbnails are 16:9, so crop. */
@Composable
fun Artwork(url: String?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    val shape = RoundedCornerShape(corner)
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(shape),
        loading = { ArtPlaceholder() },
        error = { ArtPlaceholder() },
    )
}

@Composable
private fun ArtPlaceholder() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Highlight as the playing song; by default, whenever it's the song the player has loaded. */
    isCurrent: Boolean? = null,
    downloaded: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val nowPlaying = LocalNowPlaying.current
    val current = isCurrent ?: (nowPlaying.trackId == track.id)
    Row(
        modifier
            .fillMaxWidth()
            .focusRing(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onMore)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Box(contentAlignment = Alignment.Center) {
            Artwork(track.thumbnailUrl, 52.dp)
            if (current) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.45f)))
                PlayingBars(nowPlaying.isPlaying, color = Color.White)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (downloaded) {
                    Icon(Icons.Default.DownloadDone, stringResource(R.string.track_downloaded), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    listOf(track.artist, formatDuration(track.durationSeconds)).filter { it.isNotEmpty() }.joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
        if (onMore != null) {
            IconButton(onClick = onMore, modifier = Modifier.focusRing(CircleShape)) { Icon(Icons.Default.MoreVert, stringResource(R.string.track_more_options)) }
        }
    }
}

/** A square card for horizontal carousels on the home screen. */
@Composable
fun TrackCard(track: Track, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val playing = LocalNowPlaying.current
    Column(
        modifier
            .width(148.dp)
            .focusRing(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
    ) {
        Box(contentAlignment = Alignment.BottomStart) {
            Artwork(track.thumbnailUrl, 140.dp, corner = 12.dp)
            if (playing.trackId == track.id) {
                Box(
                    Modifier.padding(8.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary).padding(6.dp),
                ) { PlayingBars(playing.isPlaying, color = MaterialTheme.colorScheme.onPrimary) }
            }
        }
        Spacer(Modifier.padding(top = 6.dp))
        Text(track.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, maxLines: Int = 2, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A little flame-gradient marker before each section title.
        Box(Modifier.size(width = 4.dp, height = 22.dp).clip(RoundedCornerShape(2.dp)).background(LocalFireBrushes.current.flame))
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) { Text(actionLabel) }
        }
    }
}

@Composable
fun ListItemRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().focusRing(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(LocalFireBrushes.current.flame),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Color.White) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

val ContentPadding = PaddingValues(bottom = 16.dp)

/** The Chromecast button; nothing on devices without Google Play services (e.g. Fire tablets). */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun CastButton(available: Boolean) {
    if (!available) return
    androidx.media3.cast.MediaRouteButton(Modifier, androidx.media3.cast.rememberMediaRouteButtonState())
}

fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
