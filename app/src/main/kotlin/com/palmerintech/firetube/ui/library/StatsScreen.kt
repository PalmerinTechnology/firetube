package com.palmerintech.firetube.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.palmerintech.firetube.AppContainer
import com.palmerintech.firetube.data.ArtistStats
import com.palmerintech.firetube.data.ListeningStats
import com.palmerintech.firetube.data.StatsPeriod
import com.palmerintech.firetube.data.formatListened
import com.palmerintech.firetube.ui.StatsViewModel
import com.palmerintech.firetube.ui.appViewModel
import com.palmerintech.firetube.ui.components.Artwork
import com.palmerintech.firetube.ui.components.EmptyState
import com.palmerintech.firetube.ui.components.LocalTrackMenu
import com.palmerintech.firetube.ui.components.SectionHeader
import com.palmerintech.firetube.ui.components.TrackRow
import com.palmerintech.firetube.ui.components.focusRing
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Your stats: listening time, plays, top songs and artists, and when you listen. All on-device. */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(container: AppContainer, onBack: () -> Unit, contentPadding: PaddingValues) {
    val vm = appViewModel { StatsViewModel(it) }
    val period by vm.period.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val menu = LocalTrackMenu.current

    Column {
        TopAppBar(
            title = { Text("Your stats") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        LazyColumn(contentPadding = contentPadding) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(StatsPeriod.entries) { p ->
                        FilterChip(
                            selected = p == period,
                            onClick = { vm.period.value = p },
                            label = { Text(p.label) },
                            modifier = Modifier.focusRing(RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }
            val s = stats
            when {
                s == null -> item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                s.isEmpty -> item {
                    EmptyState(
                        Icons.Default.BarChart,
                        if (period == StatsPeriod.ALL) "No stats yet" else "Nothing played ${period.label.lowercase()}",
                        "Play some music and your stats will show up here.",
                    )
                }
                else -> {
                    item { Totals(s) }
                    item { SectionHeader("Top songs") }
                    itemsIndexed(s.topSongs, key = { _, it -> "song:" + it.track.id }) { i, song ->
                        TrackRow(
                            song.track,
                            onClick = { container.player.play(s.topSongs.map { it.track }, i) },
                            onMore = { menu.open(song.track) },
                            leading = { Rank(i) },
                            trailing = { PlayCount(song.plays) },
                        )
                    }
                    if (s.topArtists.isNotEmpty()) {
                        item { SectionHeader("Top artists") }
                        itemsIndexed(s.topArtists, key = { _, it -> "artist:" + it.name }) { i, artist ->
                            ArtistRow(i, artist, onClick = { container.player.play(artist.tracks) })
                        }
                    }
                    item { SectionHeader("When you listen") }
                    item { HourChart(s.byHour) }
                }
            }
        }
    }
}

@Composable
private fun Totals(s: ListeningStats) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("Listening time", formatListened(s.msListened), Modifier.weight(1f))
        StatTile("Plays", s.plays.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Rank(index: Int) {
    Text(
        "${index + 1}",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(32.dp),
    )
}

@Composable
private fun PlayCount(plays: Int) {
    Text(
        if (plays == 1) "1 play" else "$plays plays",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp),
    )
}

/** Tapping an artist plays their songs from the period, most played first. */
@Composable
private fun ArtistRow(index: Int, artist: ArtistStats, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().focusRing(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Rank(index)
        Artwork(artist.thumbnailUrl, 52.dp, corner = 26.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                formatListened(artist.msListened),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        PlayCount(artist.plays)
    }
}

/** Listening time by hour of day: 24 bars, tallest = the busiest hour. */
@Composable
private fun HourChart(byHour: List<Long>) {
    val bar = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val peak = byHour.indices.maxBy { byHour[it] }.takeIf { byHour[it] > 0 }
    val hourFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    val hour = { h: Int -> LocalTime.of(h, 0).format(hourFormat) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        val busiest = peak?.let { "Most around ${hour(it)}" }
        if (busiest != null) {
            Text(
                busiest,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        Canvas(
            Modifier.fillMaxWidth().height(96.dp).semantics {
                contentDescription = listOfNotNull("Listening by hour of day", busiest?.lowercase()).joinToString(", ")
            },
        ) {
            val gap = 3.dp.toPx()
            val w = (size.width - gap * 23) / 24
            val max = byHour.max().coerceAtLeast(1).toFloat()
            byHour.forEachIndexed { h, ms ->
                val x = h * (w + gap)
                val corner = CornerRadius(w / 3)
                drawRoundRect(empty, Offset(x, 0f), Size(w, size.height), corner)
                if (ms > 0) {
                    val height = (size.height * ms / max).coerceAtLeast(3.dp.toPx())
                    drawRoundRect(bar, Offset(x, size.height - height), Size(w, height), corner)
                }
            }
        }
        // Each label starts a quarter of the day, under its first bar.
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp)) {
            listOf(0, 6, 12, 18).forEach { h ->
                Text(hour(h), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
        }
    }
}
