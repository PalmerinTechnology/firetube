package com.palmerintech.firetube.ui.settings

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import com.palmerintech.firetube.AppContainer
import com.palmerintech.firetube.BuildConfig
import com.palmerintech.firetube.Support
import com.palmerintech.firetube.ui.components.openUrl
import com.palmerintech.firetube.data.AudioQuality
import com.palmerintech.firetube.data.EqualizerPreset
import com.palmerintech.firetube.data.ThemeMode
import com.palmerintech.firetube.data.UserSettings
import com.palmerintech.firetube.data.sync.CloudSync
import com.palmerintech.firetube.player.AudioEffects
import com.palmerintech.firetube.player.Crossfade
import com.palmerintech.firetube.update.UpdateInfo
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
                .fold({ onShowMessage("Library exported") }, { onShowMessage("Export failed: ${it.message}") })
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { container.backup.import(context.contentResolver.openInputStream(uri)!!) }
                .fold({ onShowMessage("Imported $it playlists"); container.sync.syncNow() }, { onShowMessage("Import failed: ${it.message}") })
        }
    }

    fun open(url: String) {
        if (!context.openUrl(url)) onShowMessage("No browser on this device \u2014 visit $url")
    }

    Column {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        LazyColumn(contentPadding = contentPadding) {
            item { Group("Playback") }
            item {
                SwitchRow("Autoplay", "Keep playing similar songs when the queue ends", settings.autoplay) {
                    scope.launch { s.setAutoplay(it) }
                }
            }
            item {
                SwitchRow("Skip non-music sections", "Skips intros, talking and sponsor reads (SponsorBlock)", settings.sponsorBlock) {
                    scope.launch { s.setSponsorBlock(it) }
                }
            }
            item {
                SwitchRow("Even out volume", "Keeps loud and quiet songs at a similar level", settings.volumeLeveling) {
                    scope.launch { s.setVolumeLeveling(it) }
                }
            }
            item {
                SwitchRow("Data saver", "Stream lower-bitrate audio", settings.audioQuality == AudioQuality.DATA_SAVER) {
                    scope.launch { container.setAudioQuality(if (it) AudioQuality.DATA_SAVER else AudioQuality.HIGH) }
                }
            }

            item { Group("Audio") }
            // Hidden on devices without the effect.
            if (AudioEffects.equalizerSupported) {
                item { ClickRow("Equalizer", settings.equalizerPreset.label()) { equalizerDialog = true } }
            }
            if (AudioEffects.bassBoostSupported) {
                item {
                    SliderRow("Bass boost", { if (it == 0) "Off" else "$it%" }, settings.bassBoost, 0..100, step = 10) {
                        scope.launch { s.setBassBoost(it) }
                    }
                }
            }
            item {
                ClickRow("Crossfade", settings.crossfadeSeconds.let { if (it == 0) "Off — songs play back to back" else "Fades between songs over $it s" }) {
                    crossfadeDialog = true
                }
            }

            item { Group("Appearance") }
            item { ClickRow("Theme", settings.themeMode.label()) { themeDialog = true } }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                item {
                    SwitchRow("Wallpaper colors", "Match FireTube's colors to your wallpaper", settings.dynamicColor) {
                        scope.launch { s.setDynamicColor(it) }
                    }
                }
            }

            item { Group("Storage") }
            item { ClickRow("Song cache", "${settings.cacheSizeMb} MB · recently played songs replay without data") { cacheDialog = true } }
            item {
                ClickRow("Clear cache", "Downloads are kept") {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val cache = container.mediaStack.streamCache
                            cache.keys.toList().forEach(cache::removeResource)
                        }
                        onShowMessage("Cache cleared")
                    }
                }
            }

            if (container.auth.available) {
                item { Group("Account") }
                val u = user
                if (u == null) {
                    item {
                        ClickRow("Sign in with Google", "Sync playlists and favorites across your devices") {
                            scope.launch {
                                runCatching { container.auth.signIn(context) }.onFailure { onShowMessage("Sign-in failed: ${it.message}") }
                            }
                        }
                    }
                } else {
                    item {
                        ClickRow("Sync now", "${u.email ?: u.displayName} · ${syncStatus.label()}") { scope.launch { container.sync.syncNow() } }
                    }
                    item { ClickRow("Sign out", "Your library stays on this device") { scope.launch { container.auth.signOut(context) } } }
                }
            }

            if (container.firebaseConfigured) {
                item { Group("Privacy") }
                item {
                    SwitchRow("Send crash reports", "Crash details (no personal data) help fix bugs — Firebase Crashlytics", settings.crashReports) {
                        scope.launch { s.setCrashReports(it) }
                    }
                }
            }

            item { Group("Backup") }
            item { ClickRow("Export library", "Save playlists and favorites to a file") { exportLauncher.launch("firetube-backup.json") } }
            item { ClickRow("Import library", "Restore from a FireTube backup file") { importLauncher.launch(arrayOf("application/json", "*/*")) } }

            if (container.updater.supported) {
                item { Group("Updates") }
                item {
                    ClickRow("Check for updates", if (checking) "Checking…" else "Version ${BuildConfig.VERSION_NAME}") {
                        checking = true
                        scope.launch {
                            val u = container.updater.check()
                            checking = false
                            if (u == null) onShowMessage("You're up to date") else update = u
                        }
                    }
                }
                installProgress?.let { p -> item { LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(16.dp)) } }
            }

            item { Group("About") }
            item {
                ClickRow(
                    "FireTube ${BuildConfig.VERSION_NAME}",
                    "Free and ad-free",
                ) {}
            }
            Support.donateUrl?.let { url ->
                item { Group("Support FireTube") }
                item { ClickRow("Leave a tip", "FireTube is free and ad-free \u2014 tips keep it going") { open(url) } }
            }
            item { ClickRow("Source code", "FireTube is open source (GPLv3)") { open(SOURCE_URL) } }
            item {
                Text(
                    "FireTube isn't affiliated with YouTube or Google. Uses NewPipeExtractor (GPLv3), Media3 and SponsorBlock.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (themeDialog) {
        ChoiceDialog("Theme", ThemeMode.entries, settings.themeMode, { it.label() }, onDismiss = { themeDialog = false }) {
            scope.launch { s.setThemeMode(it) }; themeDialog = false
        }
    }
    if (equalizerDialog) {
        ChoiceDialog("Equalizer", EqualizerPreset.entries, settings.equalizerPreset, { it.label() }, onDismiss = { equalizerDialog = false }) {
            scope.launch { s.setEqualizerPreset(it) }; equalizerDialog = false
        }
    }
    if (crossfadeDialog) {
        val options = (0..Crossfade.MAX_SECONDS step 2).toList()
        ChoiceDialog("Crossfade", options, settings.crossfadeSeconds, { if (it == 0) "Off" else "$it seconds" }, onDismiss = { crossfadeDialog = false }) {
            scope.launch { s.setCrossfadeSeconds(it) }; crossfadeDialog = false
        }
    }
    if (cacheDialog) {
        ChoiceDialog("Song cache", listOf(256, 512, 1024, 2048, 4096), settings.cacheSizeMb, { "$it MB" }, onDismiss = { cacheDialog = false }) {
            scope.launch { s.setCacheSizeMb(it) }; cacheDialog = false
            onShowMessage("Takes effect after FireTube restarts")
        }
    }
    update?.let { u ->
        AlertDialog(
            onDismissRequest = { update = null },
            title = { Text("Update to ${u.versionName}?") },
            text = { Text(u.notes.ifBlank { "A new version of FireTube is available." }) },
            confirmButton = {
                TextButton(onClick = {
                    update = null
                    if (!context.packageManager.canRequestPackageInstalls()) {
                        onShowMessage("Allow FireTube to install updates, then try again")
                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
                        return@TextButton
                    }
                    scope.launch {
                        installProgress = 0f
                        runCatching { container.updater.install(u) { p -> installProgress = p } }
                            .onFailure { onShowMessage("Update failed: ${it.message}") }
                        installProgress = null
                    }
                }) { Text("Update") }
            },
            dismissButton = { TextButton(onClick = { update = null }) { Text("Later") } },
        )
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
private fun SliderRow(title: String, subtitle: (Int) -> String, value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit) {
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
private fun <T> ChoiceDialog(title: String, options: List<T>, selected: T, label: (T) -> String, onDismiss: () -> Unit, onSelect: (T) -> Unit) {
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> "System default"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun EqualizerPreset.label() = when (this) {
    EqualizerPreset.FLAT -> "Off (flat)"
    EqualizerPreset.BASS -> "Bass boost"
    EqualizerPreset.TREBLE -> "Treble boost"
    EqualizerPreset.VOCAL -> "Vocal"
    EqualizerPreset.ROCK -> "Rock"
    EqualizerPreset.POP -> "Pop"
    EqualizerPreset.CLASSICAL -> "Classical"
}

private fun CloudSync.Status.label() = when (this) {
    CloudSync.Status.Idle -> "Not synced yet"
    CloudSync.Status.Syncing -> "Syncing…"
    is CloudSync.Status.Done -> "Synced ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))}"
    is CloudSync.Status.Failed -> "Sync failed: $message"
}

private const val SOURCE_URL = "https://github.com/PalmerinTechnology/firetube"
