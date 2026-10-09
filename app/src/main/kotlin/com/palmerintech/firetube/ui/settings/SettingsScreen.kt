package com.palmerintech.firetube.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.palmerintech.firetube.AppContainer
import com.palmerintech.firetube.BuildConfig
import com.palmerintech.firetube.R
import com.palmerintech.firetube.Support
import com.palmerintech.firetube.ui.components.openUrl
import com.palmerintech.firetube.data.AudioQuality
import com.palmerintech.firetube.data.EqualizerPreset
import com.palmerintech.firetube.data.ThemeMode
import com.palmerintech.firetube.data.UserSettings
import com.palmerintech.firetube.data.sync.Backup
import com.palmerintech.firetube.data.sync.CloudSync
import com.palmerintech.firetube.player.AudioEffects
import com.palmerintech.firetube.player.Crossfade
import com.palmerintech.firetube.update.UpdateInfo
import com.palmerintech.firetube.ui.components.UpdateDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onShowMessage: (String) -> Unit, contentPadding: PaddingValues) {
    val settings by container.settings.settings.collectAsState(UserSettings())
    val user by container.auth.user.collectAsState()
    val syncStatus by container.sync.status.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val s = container.settings

    var cacheDialog by remember { mutableStateOf(false) }
    var themeDialog by remember { mutableStateOf(false) }
    var equalizerDialog by remember { mutableStateOf(false) }
    var crossfadeDialog by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var checking by remember { mutableStateOf(false) }
    var installProgress by remember { mutableStateOf<Float?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { context.contentResolver.openOutputStream(uri)!!.let { container.backup.export(it) } }
                .fold({ onShowMessage(resources.getString(R.string.settings_library_exported)) }, { onShowMessage(resources.getString(R.string.settings_export_failed, it.message)) })
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { container.backup.import(context.contentResolver.openInputStream(uri)!!) }
                .fold({ onShowMessage(resources.getQuantityString(R.plurals.settings_imported_playlists, it, it)); container.sync.syncNow() }, {
                    onShowMessage(
                        if (it is Backup.NotABackupException) resources.getString(R.string.settings_import_not_backup)
                        else resources.getString(R.string.settings_import_failed, it.message),
                    )
                })
        }
    }

    fun open(url: String) {
        if (!context.openUrl(url)) onShowMessage(resources.getString(R.string.no_browser, url))
    }

    Column {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
        )
        LazyColumn(contentPadding = contentPadding) {
            item { Group(stringResource(R.string.settings_group_playback)) }
            item {
                SwitchRow(stringResource(R.string.settings_autoplay), stringResource(R.string.settings_autoplay_summary), settings.autoplay) {
                    scope.launch { s.setAutoplay(it) }
                }
            }
            item {
                SwitchRow(stringResource(R.string.settings_sponsorblock), stringResource(R.string.settings_sponsorblock_summary), settings.sponsorBlock) {
                    scope.launch { s.setSponsorBlock(it) }
                }
            }
            item {
                SwitchRow(stringResource(R.string.settings_volume_leveling), stringResource(R.string.settings_volume_leveling_summary), settings.volumeLeveling) {
                    scope.launch { s.setVolumeLeveling(it) }
                }
            }
            item {
                SwitchRow(stringResource(R.string.settings_data_saver), stringResource(R.string.settings_data_saver_summary), settings.audioQuality == AudioQuality.DATA_SAVER) {
                    scope.launch { container.setAudioQuality(if (it) AudioQuality.DATA_SAVER else AudioQuality.HIGH) }
                }
            }

            item { Group(stringResource(R.string.settings_group_audio)) }
            // Hidden on devices without the effect.
            if (AudioEffects.equalizerSupported) {
                item { ClickRow(stringResource(R.string.settings_equalizer), settings.equalizerPreset.label()) { equalizerDialog = true } }
            }
            if (AudioEffects.bassBoostSupported) {
                item {
                    SliderRow(stringResource(R.string.settings_bass_boost), { if (it == 0) stringResource(R.string.settings_off) else stringResource(R.string.settings_percent, it) }, settings.bassBoost, 0..100, step = 10) {
                        scope.launch { s.setBassBoost(it) }
                    }
                }
            }
            item {
                ClickRow(stringResource(R.string.settings_crossfade), settings.crossfadeSeconds.let { if (it == 0) stringResource(R.string.settings_crossfade_off_summary) else stringResource(R.string.settings_crossfade_summary, it) }) {
                    crossfadeDialog = true
                }
            }

            item { Group(stringResource(R.string.settings_group_appearance)) }
            item { ClickRow(stringResource(R.string.settings_theme), settings.themeMode.label()) { themeDialog = true } }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                item {
                    SwitchRow(stringResource(R.string.settings_dynamic_color), stringResource(R.string.settings_dynamic_color_summary), settings.dynamicColor) {
                        scope.launch { s.setDynamicColor(it) }
                    }
                }
            }

            item { Group(stringResource(R.string.settings_group_storage)) }
            item { ClickRow(stringResource(R.string.settings_song_cache), stringResource(R.string.settings_song_cache_summary, settings.cacheSizeMb)) { cacheDialog = true } }
            item {
                ClickRow(stringResource(R.string.settings_clear_cache), stringResource(R.string.settings_clear_cache_summary)) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val cache = container.mediaStack.streamCache
                            cache.keys.toList().forEach(cache::removeResource)
                        }
                        onShowMessage(resources.getString(R.string.settings_cache_cleared))
                    }
                }
            }

            if (container.auth.available) {
                item { Group(stringResource(R.string.settings_group_account)) }
                val u = user
                if (u == null) {
                    item {
                        ClickRow(stringResource(R.string.settings_sign_in), stringResource(R.string.settings_sign_in_summary)) {
                            scope.launch {
                                runCatching { container.auth.signIn(context) }.onFailure { onShowMessage(resources.getString(R.string.settings_sign_in_failed, it.message)) }
                            }
                        }
                    }
                } else {
                    item {
                        ClickRow(stringResource(R.string.settings_sync_now), "${u.email ?: u.displayName} · ${syncStatus.label()}") { scope.launch { container.sync.syncNow() } }
                    }
                    item { ClickRow(stringResource(R.string.settings_sign_out), stringResource(R.string.settings_sign_out_summary)) { scope.launch { container.auth.signOut(context) } } }
                }
            }

            if (container.firebaseConfigured) {
                item { Group(stringResource(R.string.settings_group_privacy)) }
                item {
                    SwitchRow(stringResource(R.string.settings_crash_reports), stringResource(R.string.settings_crash_reports_summary), settings.crashReports) {
                        scope.launch { s.setCrashReports(it) }
                    }
                }
            }

            item { Group(stringResource(R.string.settings_group_backup)) }
            item { ClickRow(stringResource(R.string.settings_export), stringResource(R.string.settings_export_summary)) { exportLauncher.launch("firetube-backup.json") } }
            item { ClickRow(stringResource(R.string.settings_import), stringResource(R.string.settings_import_summary)) { importLauncher.launch(arrayOf("application/json", "*/*")) } }

            if (container.updater.supported) {
                item { Group(stringResource(R.string.settings_group_updates)) }
                item {
                    ClickRow(stringResource(R.string.settings_check_updates), if (checking) stringResource(R.string.settings_checking) else stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) {
                        checking = true
                        scope.launch {
                            val u = container.updater.check()
                            checking = false
                            if (u == null) onShowMessage(resources.getString(R.string.settings_up_to_date)) else update = u
                        }
                    }
                }
                installProgress?.let { p -> item { LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(16.dp)) } }
            }

            item { Group(stringResource(R.string.settings_group_about)) }
            item {
                ClickRow(
                    "FireTube ${BuildConfig.VERSION_NAME}",
                    stringResource(R.string.settings_about_summary),
                ) {}
            }
            Support.donateUrl?.let { url ->
                item { Group(stringResource(R.string.settings_group_support)) }
                item { ClickRow(stringResource(R.string.settings_tip), stringResource(R.string.settings_tip_summary)) { open(url) } }
            }
            item { ClickRow(stringResource(R.string.settings_source_code), stringResource(R.string.settings_source_code_summary)) { open(SOURCE_URL) } }
            item {
                Text(
                    stringResource(R.string.settings_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (themeDialog) {
        ChoiceDialog(stringResource(R.string.settings_theme), ThemeMode.entries, settings.themeMode, { it.label() }, onDismiss = { themeDialog = false }) {
            scope.launch { s.setThemeMode(it) }; themeDialog = false
        }
    }
    if (equalizerDialog) {
        ChoiceDialog(stringResource(R.string.settings_equalizer), EqualizerPreset.entries, settings.equalizerPreset, { it.label() }, onDismiss = { equalizerDialog = false }) {
            scope.launch { s.setEqualizerPreset(it) }; equalizerDialog = false
        }
    }
    if (crossfadeDialog) {
        val options = (0..Crossfade.MAX_SECONDS step 2).toList()
        ChoiceDialog(stringResource(R.string.settings_crossfade), options, settings.crossfadeSeconds, { if (it == 0) stringResource(R.string.settings_off) else pluralStringResource(R.plurals.settings_crossfade_seconds, it, it) }, onDismiss = { crossfadeDialog = false }) {
            scope.launch { s.setCrossfadeSeconds(it) }; crossfadeDialog = false
        }
    }
    if (cacheDialog) {
        ChoiceDialog(stringResource(R.string.settings_song_cache), listOf(256, 512, 1024, 2048, 4096), settings.cacheSizeMb, { stringResource(R.string.settings_cache_size, it) }, onDismiss = { cacheDialog = false }) {
            scope.launch { s.setCacheSizeMb(it) }; cacheDialog = false
            onShowMessage(resources.getString(R.string.settings_cache_restart))
        }
    }
    update?.let { u ->
        UpdateDialog(container, u, scope, onDismiss = { update = null }, onProgress = { installProgress = it }, onShowMessage = onShowMessage)
    }
}

@Composable
private fun Group(title: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
    }
}

@Composable
private fun ClickRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

/** A slider that shows its value live but saves once it's let go (not on every step of the drag). */
@Composable
private fun SliderRow(title: String, subtitle: @Composable (Int) -> String, value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit) {
    var dragging by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle(dragging.roundToInt()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { if (dragging.roundToInt() != value) onChange(dragging.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / step - 1,
        )
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { o ->
                    Row(Modifier.fillMaxWidth().clickable { onSelect(o) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(o == selected, { onSelect(o) })
                        Text(label(o))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ThemeMode.label() = stringResource(
    when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    },
)

@Composable
private fun EqualizerPreset.label() = stringResource(
    when (this) {
        EqualizerPreset.FLAT -> R.string.equalizer_flat
        EqualizerPreset.BASS -> R.string.equalizer_bass
        EqualizerPreset.TREBLE -> R.string.equalizer_treble
        EqualizerPreset.VOCAL -> R.string.equalizer_vocal
        EqualizerPreset.ROCK -> R.string.equalizer_rock
        EqualizerPreset.POP -> R.string.equalizer_pop
        EqualizerPreset.CLASSICAL -> R.string.equalizer_classical
    },
)

@Composable
private fun CloudSync.Status.label() = when (this) {
    CloudSync.Status.Idle -> stringResource(R.string.sync_status_idle)
    CloudSync.Status.Syncing -> stringResource(R.string.sync_status_syncing)
    is CloudSync.Status.Done -> stringResource(R.string.sync_status_done, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at)))
    is CloudSync.Status.Failed -> stringResource(if (message.isBlank()) R.string.sync_status_failed_no_detail else R.string.sync_status_failed, message)
}

private const val SOURCE_URL = "https://github.com/PalmerinTek/firetube"
