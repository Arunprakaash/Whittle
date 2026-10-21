package app.whittle.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.whittle.design.theme.ThemeMode
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.random.Random

/** What the user chose in Settings. Anything they haven't touched follows the bundled defaults. */
data class UserSettings(
    val wallpaperColors: Boolean,
    val haptics: Boolean,
    /** Counts, timings and errors go to the analytics tool; never messages, files or keys. */
    val shareUsageData: Boolean,
    /** Screen recordings for the analytics tool. Only recorded while [shareUsageData] is on. */
    val screenRecordings: Boolean,
    /** A task keeps running when the user leaves Whittle, with an ongoing notification to follow or stop it. */
    val keepRunningInBackground: Boolean,
    val themeMode: ThemeMode,
    /** Draws the user's avatar ([app.whittle.design.components.BeaverAvatar]); made once per phone. */
    val avatarSeed: Long? = null,
)

/** Settings saved on the phone. Only choices the user made are stored, so changed defaults still reach them. */
class SettingsStore(context: Context, name: String, private val defaults: UserSettings) {
    private val dataStore = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(name) }

    val settings: Flow<UserSettings> = dataStore.data.map { stored ->
        UserSettings(
            wallpaperColors = stored[WALLPAPER_COLORS] ?: defaults.wallpaperColors,
            haptics = stored[HAPTICS] ?: defaults.haptics,
            shareUsageData = stored[SHARE_USAGE_DATA] ?: defaults.shareUsageData,
            screenRecordings = stored[SCREEN_RECORDINGS] ?: defaults.screenRecordings,
            keepRunningInBackground = stored[KEEP_RUNNING] ?: defaults.keepRunningInBackground,
            themeMode = stored[THEME_MODE]?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved } } ?: defaults.themeMode,
            avatarSeed = stored[AVATAR_SEED],
        )
    }

    suspend fun setWallpaperColors(on: Boolean) = set(WALLPAPER_COLORS, on)

    suspend fun setHaptics(on: Boolean) = set(HAPTICS, on)

    suspend fun setShareUsageData(on: Boolean) = set(SHARE_USAGE_DATA, on)

    suspend fun setScreenRecordings(on: Boolean) = set(SCREEN_RECORDINGS, on)

    suspend fun setKeepRunningInBackground(on: Boolean) = set(KEEP_RUNNING, on)

    /** Gives this phone its avatar the first time; it stays the same after that. */
    suspend fun ensureAvatarSeed() {
        dataStore.edit { if (it[AVATAR_SEED] == null) it[AVATAR_SEED] = Random.nextLong() }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    private suspend fun set(key: Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { it[key] = value }
    }

    private companion object {
        val WALLPAPER_COLORS = booleanPreferencesKey("wallpaper_colors")
        val HAPTICS = booleanPreferencesKey("haptics")
        val SHARE_USAGE_DATA = booleanPreferencesKey("share_usage_data")
        val SCREEN_RECORDINGS = booleanPreferencesKey("screen_recordings")
        val KEEP_RUNNING = booleanPreferencesKey("keep_running_in_background")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AVATAR_SEED = longPreferencesKey("avatar_seed")
    }
}
