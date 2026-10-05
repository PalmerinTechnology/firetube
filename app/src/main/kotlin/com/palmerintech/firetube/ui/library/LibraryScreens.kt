package com.palmerintech.firetube.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.palmerintech.firetube.AppContainer
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.ui.Load
import com.palmerintech.firetube.ui.PlaylistViewModel
import com.palmerintech.firetube.ui.RemotePlaylistViewModel
import com.palmerintech.firetube.ui.appViewModel
import com.palmerintech.firetube.ui.components.Artwork
import com.palmerintech.firetube.ui.components.EmptyState
import com.palmerintech.firetube.ui.components.ListItemRow
import com.palmerintech.firetube.ui.components.LocalTrackMenu
import com.palmerintech.firetube.ui.components.SectionHeader
import com.palmerintech.firetube.ui.components.TrackRow
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

enum class TrackList(val title: String) { FAVORITES("Favorites"), HISTORY("Recently played"), DOWNLOADS("Downloads") }

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    container: AppContainer,
    onOpenPlaylist: (String) -> Unit,
    onOpenList: (TrackList) -> Unit,
    onOpenRemotePlaylist: (String) -> Unit,
    onOpenStats: () -> Unit,
    contentPadding: PaddingValues,
) {
    val playlists by container.library.playlists.collectAsState(emptyList())
    val favorites by container.library.favorites.collectAsState(emptyList())
    val downloads by container.downloads.state.collectAsState()
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<String?>(null) } // "new" | "import"

    Box(Modifier.fillMaxSize()) {
        Column {
            TopAppBar(title = { Text("Library", fontWeight = FontWeight.Bold) })
            LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 80.dp)) {
                item { ListItemRow(Icons.Default.Favorite, "Favorites", "${favorites.size} songs") { onOpenList(TrackList.FAVORITES) } }
                item {
                    ListItemRow(Icons.Default.Download, "Downloads", "${downloads.values.count { it.completed }} songs · play offline") {
                        onOpenList(TrackList.DOWNLOADS)
                    }
                }
                item { ListItemRow(Icons.Default.History, "Recently played") { onOpenList(TrackList.HISTORY) } }
                item { ListItemRow(Icons.Default.BarChart, "Your stats", "Top songs, artists and listening time", onOpenStats) }
                item { SectionHeader("Playlists") }
                if (playlists.isEmpty()) {
                    item {
                        EmptyState(Icons.Default.LibraryMusic, "No playlists yet", "Create one, or import a playlist from YouTube.")
                    }
                }
                items(playlists, key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenPlaylist(p.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(p.thumbnailUrl, 52.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            Text("${p.trackCount} songs", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    TextButton(onClick = { dialog = "import" }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Icon(Icons.Default.Link, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import a YouTube playlist")
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { dialog = "new" },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("New playlist") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        )
    }

    when (dialog) {
        "new" -> TextDialog("New playlist", "Name", "Create", onDismiss = { dialog = null }) { name ->
            scope.launch { onOpenPlaylist(container.library.createPlaylist(name)) }
            dialog = null
        }
        "import" -> TextDialog("Import from YouTube", "Playlist link", "Open", onDismiss = { dialog = null }) { url ->
            dialog = null
            onOpenRemotePlaylist(url.trim())
        }
    }
}

@Composable
fun TextDialog(title: String, label: String, confirm: String, initial: String = "", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PlayShuffleRow(tracks: List<Track>, onPlay: () -> Unit, onShuffle: () -> Unit, extra: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onPlay, enabled = tracks.isNotEmpty(), modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play")
        }
        FilledTonalButton(onClick = onShuffle, enabled = tracks.isNotEmpty(), modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle")
        }
        extra()
    }
}

@Composable
private fun Header(artwork: String?, title: String, subtitle: String) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(artwork, 112.dp, corner = 12.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A playlist in the user's library: reorder by dragging, remove via the track menu. */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(container: AppContainer, playlistId: String, onBack: () -> Unit, contentPadding: PaddingValues) {
    val vm = appViewModel(key = playlistId) { PlaylistViewModel(it, playlistId) }
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val downloads by container.downloads.state.collectAsState()
    val current by container.player.state.collectAsState()
    val menu = LocalTrackMenu.current
    var showMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Local copy for smooth dragging; committed to the database when a drag ends.
    var items by remember(tracks) { mutableStateOf(tracks.mapIndexed { i, t -> "$i:${t.id}" to t }) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val headerCount = 2
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val f = from.index - headerCount
        val t = to.index - headerCount
        if (f in items.indices && t in items.indices) items = items.toMutableList().apply { add(t, removeAt(f)) }
    }

    Column {
        TopAppBar(
            title = {},
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(showMenu, { showMenu = false }) {
                    DropdownMenuItem(text = { Text("Download all") }, onClick = { vm.downloadAll(); showMenu = false })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { renaming = true; showMenu = false })
                    DropdownMenuItem(text = { Text("Delete playlist") }, onClick = { confirmDelete = true; showMenu = false })
                }
            },
        )
        LazyColumn(state = listState, contentPadding = contentPadding) {
            item { Header(tracks.firstOrNull()?.thumbnailUrl, playlist?.name.orEmpty(), "${tracks.size} songs") }
            item {
                PlayShuffleRow(tracks, onPlay = { container.player.play(tracks) }, onShuffle = { container.player.play(tracks, shuffle = true) })
            }
            if (tracks.isEmpty()) {
                item { EmptyState(Icons.AutoMirrored.Filled.QueueMusic, "This playlist is empty", "Add songs from search with ⋮ → Add to playlist.") }
            }
            itemsIndexed(items, key = { _, it -> it.first }) { index, (key, track) ->
                ReorderableItem(reorder, key = key) {
                    TrackRow(
                        track,
                        onClick = { container.player.play(items.map { it.second }, index) },
                        onMore = { menu.open(track, "Remove from playlist") { vm.remove(index) } },
                        isCurrent = current.current?.id == track.id,
                        downloaded = downloads[track.id]?.completed == true,
                        trailing = {
                            IconButton(
                                modifier = Modifier.draggableHandle(
                                    onDragStarted = { dragFrom = items.indexOfFirst { it.first == key } },
                                    onDragStopped = {
                                        val to = items.indexOfFirst { it.first == key }
                                        if (dragFrom >= 0 && to >= 0 && dragFrom != to) vm.move(dragFrom, to)
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

    if (renaming) {
        TextDialog("Rename playlist", "Name", "Save", initial = playlist?.name.orEmpty(), onDismiss = { renaming = false }) {
            vm.rename(it); renaming = false
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${playlist?.name}”?") },
            text = { Text("This removes the playlist from your library. The songs themselves aren't affected.") },
            confirmButton = { TextButton(onClick = { vm.delete(); confirmDelete = false; onBack() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** A YouTube playlist: play it directly, or save a copy to the library. */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemotePlaylistScreen(container: AppContainer, url: String, onBack: () -> Unit, onSaved: (String) -> Unit, contentPadding: PaddingValues) {
    val vm = appViewModel(key = url) { RemotePlaylistViewModel(it, url) }
    val state by vm.state.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val menu = LocalTrackMenu.current

    Column {
        TopAppBar(title = {}, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        when (val s = state) {
            Load.Loading -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is Load.Failed -> EmptyState(Icons.Default.CloudOff, "Couldn't open playlist", s.message, actionLabel = "Retry", onAction = { vm.load() })
            is Load.Ready -> {
                val p = s.value
                val listState = rememberLazyListState()
                val nearEnd by remember { derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 5 } }
                LaunchedEffect(nearEnd, p.tracks.size) { if (nearEnd) vm.loadMore() }
                LazyColumn(state = listState, contentPadding = contentPadding) {
                    item { Header(p.summary.thumbnailUrl, p.summary.title, listOf(p.summary.owner, "${p.summary.trackCount} songs").filter { it.isNotBlank() }.joinToString(" • ")) }
                    item {
                        PlayShuffleRow(p.tracks, onPlay = { container.player.play(p.tracks) }, onShuffle = { container.player.play(p.tracks, shuffle = true) }) {
                            FilledTonalButton(onClick = { vm.save(onSaved) }, enabled = !saving) { Icon(Icons.Default.Add, "Save to library") }
                        }
                    }
                    itemsIndexed(p.tracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
                        TrackRow(t, onClick = { container.player.play(p.tracks, i) }, onMore = { menu.open(t) })
                    }
                    if (p.loadingMore) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                }
            }
        }
    }
}

/** Favorites, history and downloads share one simple list screen. */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackListScreen(container: AppContainer, list: TrackList, onBack: () -> Unit, contentPadding: PaddingValues) {
    val favorites by container.library.favorites.collectAsState(emptyList())
    val history by container.library.recent(200).collectAsState(emptyList())
    val downloads by container.downloads.state.collectAsState()
    val scope = rememberCoroutineScope()
    val menu = LocalTrackMenu.current
    val tracks = when (list) {
        TrackList.FAVORITES -> favorites
        TrackList.HISTORY -> history
        TrackList.DOWNLOADS -> downloads.values.map { it.track }
    }

    Column {
        TopAppBar(
            title = { Text(list.title) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (list == TrackList.HISTORY && history.isNotEmpty()) TextButton(onClick = { scope.launch { container.library.clearHistory() } }) { Text("Clear") }
            },
        )
        LazyColumn(contentPadding = contentPadding) {
            item { PlayShuffleRow(tracks, onPlay = { container.player.play(tracks) }, onShuffle = { container.player.play(tracks, shuffle = true) }) }
            if (tracks.isEmpty()) {
                item {
                    when (list) {
                        TrackList.FAVORITES -> EmptyState(Icons.Default.Favorite, "No favorites yet", "Tap the heart on the player to save songs here.")
                        TrackList.HISTORY -> EmptyState(Icons.Default.History, "Nothing played yet", "Songs you play show up here.")
                        TrackList.DOWNLOADS -> EmptyState(Icons.Default.Download, "No downloads", "Download songs from their ⋮ menu to play them offline.")
                    }
                }
            }
            itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
                val d = downloads[t.id]
                TrackRow(
                    t,
                    onClick = { container.player.play(tracks, i) },
                    onMore = { menu.open(t) },
                    downloaded = d?.completed == true,
                    trailing = if (list == TrackList.DOWNLOADS && d != null && !d.completed) {
                        { Text(if (d.failed) "Failed" else "${d.percent.toInt().coerceAtLeast(0)}%", style = MaterialTheme.typography.labelMedium) }
                    } else null,
                )
            }
        }
    }
}
