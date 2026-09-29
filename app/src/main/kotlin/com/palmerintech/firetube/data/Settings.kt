package com.palmerintech.firetube.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

enum class AudioQuality { HIGH, DATA_SAVER }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class UserSettings(
    /** Keep playing related songs when the queue runs out. */
    val autoplay: Boolean = true,
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    /** Skip non-music sections (intros, talking, sponsors) using SponsorBlock. */
    val sponsorBlock: Boolean = true,
    /** Even out loudness between songs. */
    val volumeLeveling: Boolean = true,
    /** Send crash reports (Firebase Crashlytics) in builds that include Firebase. */
    val crashReports: Boolean = true,
    val cacheSizeMb: Int = 512,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Off by default: FireTube looks like FireTube (red), not like the wallpaper. */
    val dynamicColor: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    /** When the Home "support FireTube" card was last dismissed (epoch ms; 0 = never). */
    val supportCardDismissedAt: Long = 0,
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val autoplay = booleanPreferencesKey("autoplay")
        val audioQuality = stringPreferencesKey("audio_quality")
        val sponsorBlock = booleanPreferencesKey("sponsor_block")
        val volumeLeveling = booleanPreferencesKey("volume_leveling")
        val crashReports = booleanPreferencesKey("crash_reports")
        val cacheSizeMb = intPreferencesKey("cache_size_mb")
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val recentSearches = stringPreferencesKey("recent_searches")
        val supportCardDismissedAt = longPreferencesKey("support_card_dismissed_at")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { p ->
        val defaults = UserSettings()
        UserSettings(
            autoplay = p[Keys.autoplay] ?: defaults.autoplay,
            audioQuality = p[Keys.audioQuality]?.let { runCatching { AudioQuality.valueOf(it) }.getOrNull() } ?: defaults.audioQuality,
            sponsorBlock = p[Keys.sponsorBlock] ?: defaults.sponsorBlock,
            volumeLeveling = p[Keys.volumeLeveling] ?: defaults.volumeLeveling,
            crashReports = p[Keys.crashReports] ?: defaults.crashReports,
            cacheSizeMb = p[Keys.cacheSizeMb] ?: defaults.cacheSizeMb,
            themeMode = p[Keys.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: defaults.themeMode,
            dynamicColor = p[Keys.dynamicColor] ?: defaults.dynamicColor,
            recentSearches = p[Keys.recentSearches]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList(),
            supportCardDismissedAt = p[Keys.supportCardDismissedAt] ?: 0,
        )
    }

    suspend fun current(): UserSettings = settings.first()

    suspend fun setAutoplay(value: Boolean) = context.dataStore.edit { it[Keys.autoplay] = value }
    suspend fun setAudioQuality(value: AudioQuality) {
        context.dataStore.edit { it[Keys.audioQuality] = value.name }
        startupPrefs.edit().putString(Keys.audioQuality.name, value.name).apply()
    }

    /** Audio quality without suspending: the song cache keys on it from player threads. */
    fun audioQualityNow(): AudioQuality =
        startupPrefs.getString(Keys.audioQuality.name, null)?.let { runCatching { AudioQuality.valueOf(it) }.getOrNull() }
            ?: UserSettings().audioQuality
    suspend fun setSponsorBlock(value: Boolean) = context.dataStore.edit { it[Keys.sponsorBlock] = value }
    suspend fun setVolumeLeveling(value: Boolean) = context.dataStore.edit { it[Keys.volumeLeveling] = value }
    suspend fun setCrashReports(value: Boolean) = context.dataStore.edit { it[Keys.crashReports] = value }
    suspend fun setCacheSizeMb(value: Int) {
        context.dataStore.edit { it[Keys.cacheSizeMb] = value }
        // Mirrored for the one synchronous read at player start-up (see [cacheSizeMbNow]).
        startupPrefs.edit().putInt(Keys.cacheSizeMb.name, value).apply()
    }

    /** Song-cache size without suspending: the media cache is built on the main thread. */
    fun cacheSizeMbNow(): Int = startupPrefs.getInt(Keys.cacheSizeMb.name, UserSettings().cacheSizeMb)

    private val startupPrefs by lazy { context.getSharedPreferences("startup", Context.MODE_PRIVATE) }

    suspend fun setThemeMode(value: ThemeMode) = context.dataStore.edit { it[Keys.themeMode] = value.name }
    suspend fun setDynamicColor(value: Boolean) = context.dataStore.edit { it[Keys.dynamicColor] = value }

    suspend fun addRecentSearch(query: String) = context.dataStore.edit { p ->
        val q = query.trim().replace('\n', ' ')
        if (q.isEmpty()) return@edit
        val existing = p[Keys.recentSearches]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        p[Keys.recentSearches] = (listOf(q) + existing.filterNot { it.equals(q, ignoreCase = true) }).take(15).joinToString("\n")
    }

    suspend fun dismissSupportCard() = context.dataStore.edit { it[Keys.supportCardDismissedAt] = System.currentTimeMillis() }

    suspend fun clearRecentSearches() = context.dataStore.edit { it.remove(Keys.recentSearches) }
}
