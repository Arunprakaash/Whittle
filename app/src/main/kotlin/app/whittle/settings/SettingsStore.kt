package app.whittle.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
    /** Whittle asks GitHub once a day whether a newer version is out (see [app.whittle.updates.UpdateCheck]). */
    val checkForUpdates: Boolean,
    /** Draws the user's avatar ([app.whittle.design.components.BeaverAvatar]); made once per phone. */
    val avatarSeed: Long? = null,
    /** Onboarding offered Linux's setup once; it isn't offered there again, set up or not. */
    val linuxOffered: Boolean = false,
    /** The phone features (ids from phone-actions.json) the user switched off; Whittle doesn't use them. */
    val phoneOff: Set<String> = emptySet(),
    /** After a chat goes quiet Whittle reviews it for facts worth remembering; each review is one small model call on the user's key. */
    val suggestMemories: Boolean = true,
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
            checkForUpdates = stored[CHECK_FOR_UPDATES] ?: defaults.checkForUpdates,
            avatarSeed = stored[AVATAR_SEED],
            linuxOffered = stored[LINUX_OFFERED] ?: false,
            phoneOff = stored[PHONE_OFF].orEmpty(),
            suggestMemories = stored[SUGGEST_MEMORIES] ?: defaults.suggestMemories,
        )
    }

    suspend fun setWallpaperColors(on: Boolean) = set(WALLPAPER_COLORS, on)

    suspend fun setHaptics(on: Boolean) = set(HAPTICS, on)

    suspend fun setShareUsageData(on: Boolean) = set(SHARE_USAGE_DATA, on)

    suspend fun setScreenRecordings(on: Boolean) = set(SCREEN_RECORDINGS, on)

    suspend fun setKeepRunningInBackground(on: Boolean) = set(KEEP_RUNNING, on)

    suspend fun setLinuxOffered() = set(LINUX_OFFERED, true)

    suspend fun setCheckForUpdates(on: Boolean) = set(CHECK_FOR_UPDATES, on)

    suspend fun setSuggestMemories(on: Boolean) = set(SUGGEST_MEMORIES, on)

    /** Lets Whittle use the phone feature [id] again, or stops it. */
    suspend fun setPhoneFeature(id: String, on: Boolean) {
        dataStore.edit { it[PHONE_OFF] = if (on) it[PHONE_OFF].orEmpty() - id else it[PHONE_OFF].orEmpty() + id }
    }

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
        val LINUX_OFFERED = booleanPreferencesKey("linux_offered")
        val PHONE_OFF = stringSetPreferencesKey("phone_off")
        val CHECK_FOR_UPDATES = booleanPreferencesKey("check_for_updates")
        val SUGGEST_MEMORIES = booleanPreferencesKey("suggest_memories")
    }
}
