package com.palmerintech.firetube.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import com.palmerintech.firetube.ui.theme.LocalFireBrushes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.LinearProgressIndicator
import com.palmerintech.firetube.ui.components.UpdateDialog
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.palmerintech.firetube.AppContainer
import com.palmerintech.firetube.Support
import com.palmerintech.firetube.ui.HomeViewModel
import com.palmerintech.firetube.ui.components.SupportCard
import com.palmerintech.firetube.ui.components.openUrl
import com.palmerintech.firetube.ui.Load
import com.palmerintech.firetube.ui.appViewModel
import com.palmerintech.firetube.ui.components.Artwork
import com.palmerintech.firetube.ui.components.CastButton
import com.palmerintech.firetube.ui.components.EmptyState
import com.palmerintech.firetube.ui.components.LocalTrackMenu
import com.palmerintech.firetube.ui.components.SectionHeader
import com.palmerintech.firetube.ui.components.TrackCard
import com.palmerintech.firetube.ui.components.TrackRow
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.palmerintech.firetube.R
import com.palmerintech.firetube.ui.message

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenPlaylist: (String) -> Unit,
    onOpenSettings: () -> Unit,
    contentPadding: PaddingValues,
    onShowMessage: (String) -> Unit = {},
) {
    val vm = appViewModel { HomeViewModel(it) }
    val trending by vm.trending.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val forYou by vm.forYou.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    var showUpdate by remember { mutableStateOf(false) }
    var installProgress by remember { mutableStateOf<Float?>(null) }
    val updateScope = rememberCoroutineScope()
    val showSupport by vm.showSupportCard.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val player = container.player
    val menu = LocalTrackMenu.current

    Box {
    // Red wash behind the header; the list scrolls over it.
    Box(Modifier.fillMaxWidth().height(280.dp).background(LocalFireBrushes.current.headerWash))
    Column {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            title = {
                // The flame keeps its gradient; the letters take the theme's text color, which
                // follows FireTube's own Light/Dark setting (a color resource would follow the system).
                val name = stringResource(R.string.app_name)
                Box(Modifier.height(25.dp).semantics { heading(); contentDescription = name }) {
                    Image(painterResource(R.drawable.wordmark_flame), contentDescription = null)
                    Image(
                        painterResource(R.drawable.wordmark_letters), contentDescription = null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                    )
                }
            },
            actions = {
                CastButton(container.castAvailable)
                IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings_title)) }
            },
        )
        LazyColumn(contentPadding = contentPadding) {
            update?.let { u ->
                item {
                    Card(
                        // Straight to the update, not to Settings; ignored while one is downloading.
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                            .clickable(enabled = installProgress == null) { showUpdate = true },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SystemUpdate, null)
                            Column(Modifier.padding(start = 16.dp)) {
                                Text(stringResource(R.string.home_update_available, u.versionName), fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.home_update_tap), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        installProgress?.let { p ->
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
                        }
                    }
                    if (showUpdate) {
                        UpdateDialog(container.updater, u, updateScope, onDismiss = { showUpdate = false }, onProgress = { installProgress = it }, onShowMessage = onShowMessage)
                    }
                }
            }

            if (showSupport) {
                item {
                    SupportCard(
                        onSupport = {
                            Support.donateUrl?.let { url -> if (!context.openUrl(url)) onShowMessage(resources.getString(R.string.no_browser, url)) }
                            vm.dismissSupportCard()
                        },
                        onDismiss = vm::dismissSupportCard,
                    )
                }
            }

            if (recent.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.home_jump_back_in)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(recent, key = { _, t -> t.id }) { i, t ->
                            TrackCard(t, onClick = { player.play(recent, i) }, onLongClick = { menu.open(t) })
                        }
                    }
                }
            }

            forYou?.let { (seed, related) ->
                item { SectionHeader(stringResource(R.string.home_because_you_played, seed.title), maxLines = 1) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(related, key = { _, t -> t.id }) { i, t ->
                            TrackCard(t, onClick = { player.play(related, i) }, onLongClick = { menu.open(t) })
                        }
                    }
                }
            }

            if (playlists.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.home_your_playlists)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(playlists, key = { it.id }) { p ->
                            Column(Modifier.width(148.dp).clickable { onOpenPlaylist(p.id) }.padding(4.dp)) {
                                Artwork(p.thumbnailUrl, 140.dp, corner = 12.dp)
                                Text(p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                Text(pluralStringResource(R.plurals.song_count, p.trackCount, p.trackCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            item {
                SectionHeader(stringResource(R.string.home_trending)) {
                    val list = (trending as? Load.Ready)?.value
                    if (!list.isNullOrEmpty()) TextButton(onClick = { player.play(list, shuffle = true) }) { Text(stringResource(R.string.player_shuffle)) }
                }
            }
            when (val t = trending) {
                Load.Loading -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                is Load.Failed -> item {
                    EmptyState(Icons.Default.CloudOff, stringResource(R.string.home_trending_error), t.message, actionLabel = stringResource(R.string.action_retry), onAction = vm::refresh)
                }
                is Load.Ready -> itemsIndexed(t.value.take(50), key = { _, tr -> "trend-" + tr.id }) { i, tr ->
                    TrackRow(tr, onClick = { player.play(t.value, i) }, onMore = { menu.open(tr) })
                }
            }
        }
    }
    }
}
