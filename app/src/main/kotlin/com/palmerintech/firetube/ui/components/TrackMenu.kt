package com.palmerintech.firetube.ui.components

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.palmerintech.firetube.AppContainer
import com.palmerintech.firetube.extractor.Track
import kotlinx.coroutines.launch

/** Opens the per-track actions sheet from anywhere in the UI. */
class TrackMenuController {
    var target by mutableStateOf<MenuTarget?>(null)

    fun open(track: Track, removeLabel: String? = null, onRemove: (() -> Unit)? = null) {
        target = MenuTarget(track, removeLabel, onRemove)
    }

    data class MenuTarget(val track: Track, val removeLabel: String?, val onRemove: (() -> Unit)?)
}

val LocalTrackMenu = staticCompositionLocalOf { TrackMenuController() }

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMenuHost(container: AppContainer, controller: TrackMenuController, onShowMessage: (String) -> Unit) {
    val target = controller.target ?: return
    val track = target.track
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isFavorite by container.library.isFavorite(track.id).collectAsState(false)
    val downloads by container.downloads.state.collectAsState()
    val download = downloads[track.id]
    var addToPlaylist by rememberSaveable { mutableStateOf(false) }

    val dismiss = { controller.target = null }

    if (addToPlaylist) {
        AddToPlaylistDialog(
            container = container,
            tracks = listOf(track),
            onDone = { name ->
                addToPlaylist = false
                dismiss()
                if (name != null) onShowMessage("Added to $name")
            },
        )
        return
    }

    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track.thumbnailUrl, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            MenuItem(Icons.AutoMirrored.Filled.PlaylistPlay, "Play next") {
                container.player.playNext(track); dismiss(); onShowMessage("Playing next")
            }
            MenuItem(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue") {
                container.player.enqueue(listOf(track)); dismiss(); onShowMessage("Added to queue")
            }
            MenuItem(Icons.Default.Radio, "Start radio") {
                // A one-song queue; autoplay fills it with related songs.
                container.player.play(listOf(track)); dismiss()
            }
            MenuItem(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (isFavorite) "Remove from favorites" else "Add to favorites") {
                scope.launch { container.library.toggleFavorite(track) }; dismiss()
            }
            MenuItem(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist") { addToPlaylist = true }
            when {
                download == null || download.failed -> MenuItem(Icons.Default.Download, "Download") {
                    container.downloads.download(track); dismiss(); onShowMessage("Downloading")
                }
                else -> MenuItem(Icons.Default.DownloadDone, if (download.completed) "Remove download" else "Cancel download") {
                    container.downloads.remove(track.id); dismiss()
                }
            }
            MenuItem(Icons.Default.Share, "Share") {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, track.url)
                context.startActivity(Intent.createChooser(send, null))
                dismiss()
            }
            if (target.removeLabel != null && target.onRemove != null) {
                MenuItem(Icons.Default.Delete, target.removeLabel) { target.onRemove.invoke(); dismiss() }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Pick an existing playlist or create a new one. [onDone] gets the playlist name, or null if cancelled. */
@UnstableApi
@Composable
fun AddToPlaylistDialog(container: AppContainer, tracks: List<Track>, onDone: (String?) -> Unit) {
    val playlists by container.library.playlists.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    if (creating) {
        AlertDialog(
            onDismissRequest = { onDone(null) },
            title = { Text("New playlist") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch { container.library.createPlaylist(name, tracks); onDone(name.trim()) }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Add to playlist") },
        text = {
            LazyColumn {
                item { MenuItem(Icons.Default.Add, "New playlist") { creating = true } }
                items(playlists, key = { it.id }) { p ->
                    MenuItem(Icons.AutoMirrored.Filled.QueueMusic, p.name) {
                        scope.launch { container.library.addToPlaylist(p.id, tracks); onDone(p.name) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}
