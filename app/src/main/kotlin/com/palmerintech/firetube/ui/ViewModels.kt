package com.palmerintech.firetube.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import com.palmerintech.firetube.AppContainer
import com.palmerintech.firetube.FireTubeApp
import com.palmerintech.firetube.Support
import com.palmerintech.firetube.data.db.PlaylistWithCount
import com.palmerintech.firetube.extractor.NewPipeStreamSource
import com.palmerintech.firetube.extractor.PageToken
import com.palmerintech.firetube.extractor.PlaylistSummary
import com.palmerintech.firetube.extractor.SearchFilter
import com.palmerintech.firetube.extractor.SearchResult
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.update.UpdateInfo
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@UnstableApi
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val app = LocalContext.current.applicationContext as FireTubeApp
    return viewModel(key = key, factory = viewModelFactory { initializer { create(app.container) } })
}

/** Identity of a search result, used as its list key. */
val SearchResult.stableKey: String
    get() = when (this) {
        is SearchResult.TrackResult -> "t:" + track.id
        is SearchResult.PlaylistResult -> "p:" + playlist.url
    }

/** Loading / content / error for network-backed screens. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

private fun Throwable.friendly(): String = when {
    message?.contains("Unable to resolve host", true) == true -> "You're offline."
    else -> message?.takeIf { it.length < 140 } ?: "Something went wrong."
}

// ---------------------------------------------------------------- Home

@UnstableApi
class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val _trending = MutableStateFlow<Load<List<Track>>>(Load.Loading)
    val trending: StateFlow<Load<List<Track>>> = _trending.asStateFlow()

    val recent = c.library.recent(20).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlists: StateFlow<List<PlaylistWithCount>> =
        c.library.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** "Because you played X": related songs for the most recent play. */
    private val _forYou = MutableStateFlow<Pair<Track, List<Track>>?>(null)
    val forYou: StateFlow<Pair<Track, List<Track>>?> = _forYou.asStateFlow()

    /**
     * The occasional "support FireTube" card: never for supporters, not until someone has
     * actually been using the app, and at most once a month after "Not now".
     */
    val showSupportCard: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(c.library.playCount, c.settings.settings) { plays, s ->
            Support.donateUrl != null && plays >= Support.PLAYS_BEFORE_ASKING &&
                System.currentTimeMillis() - s.supportCardDismissedAt > Support.ASK_AGAIN_AFTER_MS
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun dismissSupportCard() = viewModelScope.launch { c.settings.dismissSupportCard() }

    private val _update = MutableStateFlow<UpdateInfo?>(null)
    val update: StateFlow<UpdateInfo?> = _update.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { _update.value = c.updater.check() }
        viewModelScope.launch {
            c.library.recent(1).map { it.firstOrNull() }.distinctUntilChanged().filter { it != null }.collect { seed ->
                // Song-length only: related lists also contain hour-long mixes and compilations.
                val related = runCatching { c.resolver.resolve(seed!!.id).related }.getOrDefault(emptyList())
                    .filter { it.durationSeconds in 60..900 }
                _forYou.value = if (related.isEmpty()) null else seed!! to related.distinctBy { it.id }.take(15)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _trending.value = Load.Loading
            _trending.value = runCatching { c.source.trending() }
                .fold({ Load.Ready(it) }, { Load.Failed(it.friendly()) })
        }
    }
}

// ---------------------------------------------------------------- Search

@UnstableApi
class SearchViewModel(private val c: AppContainer) : ViewModel() {
    data class Results(val items: List<SearchResult>, val next: PageToken?, val loadingMore: Boolean = false)

    /**
     * Compose state, not a StateFlow: a text field fed through a flow round-trip drops or
     * duplicates characters when typing fast.
     */
    var query by mutableStateOf("")
    val filter = MutableStateFlow(SearchFilter.SONGS)

    private val _submitted = MutableStateFlow<String?>(null)
    val submitted: StateFlow<String?> = _submitted.asStateFlow()

    private val _results = MutableStateFlow<Load<Results>?>(null)
    val results: StateFlow<Load<Results>?> = _results.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    val recentSearches = c.settings.settings.map { it.recentSearches }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var searchJob: Job? = null

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            snapshotFlow { query }.debounce(250).distinctUntilChanged().collect { q ->
                _suggestions.value = if (q.isBlank() || q.startsWith("http")) emptyList()
                else runCatching { c.source.suggestions(q) }.getOrDefault(emptyList()).take(8)
            }
        }
    }

    fun submit(q: String = query) {
        val text = q.trim()
        if (text.isEmpty()) return
        query = text
        _submitted.value = text
        _suggestions.value = emptyList()
        viewModelScope.launch { c.settings.addRecentSearch(text) }
        runSearch()
    }

    fun setFilter(f: SearchFilter) {
        filter.value = f
        if (_submitted.value != null) runSearch()
    }

    fun clear() {
        query = ""
        _submitted.value = null
        _results.value = null
    }

    fun clearRecent() = viewModelScope.launch { c.settings.clearRecentSearches() }

    private fun runSearch() {
        val q = _submitted.value ?: return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _results.value = Load.Loading
            _results.value = runCatching { c.source.search(q, filter.value) }
                .fold({ Load.Ready(Results(it.items.distinctBy { r -> r.stableKey }, it.next)) }, { Load.Failed(it.friendly()) })
        }
    }

    fun loadMore() {
        val q = _submitted.value ?: return
        val f = filter.value
        val current = (_results.value as? Load.Ready)?.value ?: return
        val token = current.next ?: return
        if (current.loadingMore) return
        _results.value = Load.Ready(current.copy(loadingMore = true))
        viewModelScope.launch {
            val page = runCatching { c.source.searchMore(q, f, token) }.getOrNull()
            // A new search (or filter) started meanwhile: this page belongs to the old one.
            if (_submitted.value != q || filter.value != f) return@launch
            val latest = (_results.value as? Load.Ready)?.value ?: return@launch
            _results.value = Load.Ready(
                if (page == null) latest.copy(loadingMore = false, next = null)
                // YouTube pages overlap and repeat items; list keys must be unique or Compose crashes.
                else Results((latest.items + page.items).distinctBy { it.stableKey }, page.next),
            )
        }
    }

    /** Plays a single video from a pasted/shared link once its details are known. */
    fun playLink(videoId: String) = viewModelScope.launch {
        val track = runCatching { c.resolver.resolve(videoId).track }.getOrNull() ?: return@launch
        c.player.play(listOf(track))
    }

    /** A pasted YouTube link: a video id to play, or a playlist URL to open. */
    fun parseLink(text: String): Link? {
        if (!text.contains("youtu")) return null
        if (text.contains("list=")) return Link.Playlist(text.trim())
        return NewPipeStreamSource.videoId(text)?.let(Link::Video)
    }

    sealed interface Link {
        data class Video(val id: String) : Link
        data class Playlist(val url: String) : Link
    }
}

// ---------------------------------------------------------------- Playlists

@UnstableApi
class PlaylistViewModel(private val c: AppContainer, val playlistId: String) : ViewModel() {
    val playlist = c.library.playlist(playlistId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val tracks = c.library.playlistTracks(playlistId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun rename(name: String) = viewModelScope.launch { c.library.renamePlaylist(playlistId, name) }
    fun delete() = viewModelScope.launch { c.library.deletePlaylist(playlistId) }
    fun remove(index: Int) = viewModelScope.launch { c.library.removeFromPlaylist(playlistId, index) }
    fun move(from: Int, to: Int) = viewModelScope.launch { c.library.movePlaylistTrack(playlistId, from, to) }
    fun downloadAll() = tracks.value.forEach(c.downloads::download)
}

/** A YouTube playlist opened from search or a link, before (or without) saving it. */
@UnstableApi
class RemotePlaylistViewModel(private val c: AppContainer, val url: String) : ViewModel() {
    data class State(val summary: PlaylistSummary, val tracks: List<Track>, val next: PageToken?, val loadingMore: Boolean = false)

    private val _state = MutableStateFlow<Load<State>>(Load.Loading)
    val state: StateFlow<Load<State>> = _state.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = Load.Loading
        _state.value = runCatching { c.source.playlist(url) }
            .fold({ (s, page) -> Load.Ready(State(s, page.items, page.next)) }, { Load.Failed(it.friendly()) })
    }

    fun loadMore() {
        val s = (_state.value as? Load.Ready)?.value ?: return
        val token = s.next ?: return
        if (s.loadingMore) return
        _state.value = Load.Ready(s.copy(loadingMore = true))
        viewModelScope.launch {
            val page = runCatching { c.source.playlistMore(url, token) }.getOrNull()
            _state.value = Load.Ready(if (page == null) s.copy(next = null) else s.copy(tracks = s.tracks + page.items, next = page.next))
        }
    }

    /** Saves the whole playlist (up to [MAX_IMPORT] songs) to the library. */
    fun save(onSaved: (String) -> Unit) {
        val s = (_state.value as? Load.Ready)?.value ?: return
        viewModelScope.launch {
            _saving.value = true
            var tracks = s.tracks
            var token = s.next
            while (token != null && tracks.size < MAX_IMPORT) {
                val page = runCatching { c.source.playlistMore(url, token) }.getOrNull() ?: break
                tracks = tracks + page.items
                token = page.next
            }
            val id = c.library.createPlaylist(s.summary.title, tracks.distinctBy { it.id }.take(MAX_IMPORT), sourceUrl = url)
            _saving.value = false
            onSaved(id)
        }
    }

    private companion object {
        const val MAX_IMPORT = 1000
    }
}
