package com.palmerintech.firetube.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.palmerintech.firetube.AppContainer
import com.palmerintech.firetube.extractor.SearchFilter
import com.palmerintech.firetube.extractor.SearchResult
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.ui.Load
import com.palmerintech.firetube.ui.SearchViewModel
import com.palmerintech.firetube.ui.stableKey
import com.palmerintech.firetube.ui.appViewModel
import com.palmerintech.firetube.ui.components.Artwork
import com.palmerintech.firetube.ui.components.EmptyState
import com.palmerintech.firetube.ui.components.LocalTrackMenu
import com.palmerintech.firetube.ui.components.TrackRow

@UnstableApi
@Composable
fun SearchScreen(
    container: AppContainer,
    onOpenRemotePlaylist: (String) -> Unit,
    contentPadding: PaddingValues,
    autoFocus: Boolean,
) {
    val vm = appViewModel { SearchViewModel(it) }
    val query = vm.query
    val filter by vm.filter.collectAsStateWithLifecycle()
    val submitted by vm.submitted.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val recentSearches by vm.recentSearches.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val menu = LocalTrackMenu.current

    LaunchedEffect(Unit) { if (autoFocus && submitted == null) runCatching { focusRequester.requestFocus() } }

    fun submit(text: String) {
        when (val link = vm.parseLink(text)) {
            is SearchViewModel.Link.Video -> container.player.play(listOf(Track(link.id, "Loading…", "", 0, null)))
            is SearchViewModel.Link.Playlist -> onOpenRemotePlaylist(link.url)
            null -> vm.submit(text)
        }
        focus.clearFocus()
    }

    Column(Modifier.statusBarsPadding()) {
        OutlinedTextField(
            value = query,
            onValueChange = { vm.query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focusRequester),
            placeholder = { Text("Songs, artists, or a YouTube link") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = vm::clear) { Icon(Icons.Default.Clear, "Clear") }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit(query) }),
        )

        val showSuggestions = suggestions.isNotEmpty() && query != submitted
        when {
            showSuggestions -> LazyColumn(contentPadding = contentPadding) {
                items(suggestions) { s -> SuggestionRow(Icons.Default.Search, s) { submit(s) } }
            }
            submitted == null -> LazyColumn(contentPadding = contentPadding) {
                if (recentSearches.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Recent searches", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.clearRecent() }) { Text("Clear") }
                        }
                    }
                    items(recentSearches) { s -> SuggestionRow(Icons.Default.History, s) { submit(s) } }
                } else {
                    item { EmptyState(Icons.Default.Search, "Find something to play", "Search for a song or artist, or paste a YouTube link.") }
                }
            }
            else -> {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SearchFilter.entries) { f ->
                        FilterChip(selected = f == filter, onClick = { vm.setFilter(f) }, label = { Text(f.label()) })
                    }
                }
                Results(container, results, onOpenRemotePlaylist, vm::loadMore, contentPadding, onMore = { menu.open(it) })
            }
        }
    }
}

@UnstableApi
@Composable
private fun Results(
    container: AppContainer,
    results: Load<SearchViewModel.Results>?,
    onOpenRemotePlaylist: (String) -> Unit,
    loadMore: () -> Unit,
    contentPadding: PaddingValues,
    onMore: (Track) -> Unit,
) {
    when (results) {
        null, Load.Loading -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Load.Failed -> EmptyState(Icons.Default.CloudOff, "Search failed", results.message)
        is Load.Ready -> {
            val r = results.value
            if (r.items.isEmpty()) {
                EmptyState(Icons.Default.SearchOff, "No results", "Try different words.")
                return
            }
            val listState = rememberLazyListState()
            val nearEnd by remember { derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 5 } }
            LaunchedEffect(nearEnd, r.items.size) { if (nearEnd) loadMore() }
            LazyColumn(state = listState, contentPadding = contentPadding) {
                items(r.items, key = { it.stableKey }) { item ->
                    when (item) {
                        // Tapping a song plays it as a radio: that song, then related songs (autoplay).
                        is SearchResult.TrackResult -> TrackRow(item.track, onClick = { container.player.play(listOf(item.track)) }, onMore = { onMore(item.track) })
                        is SearchResult.PlaylistResult -> Row(
                            Modifier.fillMaxWidth().clickable { onOpenRemotePlaylist(item.playlist.url) }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(item.playlist.thumbnailUrl, 52.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.playlist.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(
                                    listOf(item.playlist.owner, "${item.playlist.trackCount} songs").filter { it.isNotBlank() }.joinToString(" • "),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                )
                            }
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, null)
                        }
                    }
                }
                if (r.loadingMore) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            }
        }
    }
}

@Composable
private fun SuggestionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun SearchFilter.label() = when (this) {
    SearchFilter.SONGS -> "Songs"
    SearchFilter.VIDEOS -> "Videos"
    SearchFilter.PLAYLISTS -> "Playlists"
}

