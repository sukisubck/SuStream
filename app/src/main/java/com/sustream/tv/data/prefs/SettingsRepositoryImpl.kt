package com.sustream.tv.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sustream.tv.core.log.AppLog
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.domain.model.AppSettings
import com.sustream.tv.domain.model.ChannelCategory
import com.sustream.tv.domain.model.IptvPreferences
import com.sustream.tv.domain.model.NotificationPreferences
import com.sustream.tv.domain.model.PlaybackPreferences
import com.sustream.tv.domain.model.PreferredQuality
import com.sustream.tv.domain.model.SubtitlePreferences
import com.sustream.tv.domain.model.SubtitleTextSize
import com.sustream.tv.domain.model.StremioAddonPreferences
import com.sustream.tv.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private const val TAG = "Settings"

/** Single DataStore instance per process, as DataStore requires. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sustream_settings",
)

/**
 * Device-local preferences on DataStore.
 *
 * DataStore rather than `SharedPreferences` because reads are a `Flow`: a settings change
 * propagates to the player, the home screen and the IPTV module without anyone wiring a listener,
 * and writes are transactional so a torn write cannot leave a half-applied setting.
 *
 * Every read is defended with `catch`. A corrupt preferences file — which does happen when a Fire
 * TV loses power mid-write — would otherwise throw on every collection and make the app unusable
 * rather than merely resetting one setting.
 */
class SettingsRepositoryImpl(
    context: Context,
) : SettingsRepository {

    private val dataStore = context.settingsDataStore

    override fun observeSettings(): Flow<AppSettings> =
        dataStore.data
            .catch { throwable ->
                if (throwable is IOException) {
                    AppLog.e(TAG, "Settings unreadable; falling back to defaults", throwable)
                    emit(emptyPreferences())
                } else {
                    throw throwable
                }
            }
            .map { it.toSettings() }

    override suspend fun current(): AppSettings = observeSettings().first()

    override suspend fun updatePlayback(
        transform: (PlaybackPreferences) -> PlaybackPreferences,
    ): AppResult<Unit> = edit { prefs ->
        val updated = transform(prefs.toSettings().playback)
        prefs[Keys.AUTOPLAY_NEXT] = updated.autoplayNextEpisode
        prefs[Keys.PREFERRED_QUALITY] = updated.preferredQuality.name
        prefs[Keys.COMPLETION_THRESHOLD] = updated.completionThreshold
        prefs[Keys.WIFI_ONLY] = updated.playOnWifiOnly
        prefs[Keys.SEEK_STEP_SECONDS] = updated.seekStepSeconds
    }

    override suspend fun updateSubtitles(
        transform: (SubtitlePreferences) -> SubtitlePreferences,
    ): AppResult<Unit> = edit { prefs ->
        val updated = transform(prefs.toSettings().subtitles)
        // A null language means "no preference"; DataStore has no null, so the key is removed.
        if (updated.preferredLanguage == null) {
            prefs.remove(Keys.SUBTITLE_LANGUAGE)
        } else {
            prefs[Keys.SUBTITLE_LANGUAGE] = updated.preferredLanguage
        }
        prefs[Keys.SUBTITLES_ON_BY_DEFAULT] = updated.enabledByDefault
        prefs[Keys.SUBTITLE_TEXT_SIZE] = updated.textSize.name
        prefs[Keys.SUBTITLE_BACKGROUND_OPACITY] = updated.backgroundOpacity
        prefs[Keys.SUBTITLE_PREFER_SDH] = updated.preferSdh
    }

    override suspend fun updateIptv(
        transform: (IptvPreferences) -> IptvPreferences,
    ): AppResult<Unit> = edit { prefs ->
        val updated = transform(prefs.toSettings().iptv)
        prefs[Keys.ALLOW_LOCAL_PLAYLISTS] = updated.allowLocalNetworkPlaylists
        prefs[Keys.IPTV_REFRESH_ON_OPEN] = updated.refreshOnOpen
        prefs[Keys.SHOW_CHANNEL_NUMBERS] = updated.showChannelNumbers
        prefs[Keys.IPTV_LAST_CATEGORY] = updated.lastCategory
    }

    override suspend fun updateStremioAddon(
        transform: (StremioAddonPreferences) -> StremioAddonPreferences,
    ): AppResult<Unit> = edit { prefs ->
        val updated = transform(prefs.toSettings().stremioAddon)
        if (updated.baseUrl.isNullOrBlank()) prefs.remove(Keys.STREMIO_ADDON_URL)
        else prefs[Keys.STREMIO_ADDON_URL] = updated.baseUrl.trim()
        if (updated.displayName.isNullOrBlank()) prefs.remove(Keys.STREMIO_ADDON_NAME)
        else prefs[Keys.STREMIO_ADDON_NAME] = updated.displayName.trim()
        prefs[Keys.STREMIO_ADDON_AUTHORISED] = updated.authorisedByUser
    }

    override suspend fun updateNotifications(
        transform: (NotificationPreferences) -> NotificationPreferences,
    ): AppResult<Unit> = edit { prefs ->
        val updated = transform(prefs.toSettings().notifications)
        prefs[Keys.NOTIFY_SERVICE] = updated.serviceAlerts
        prefs[Keys.NOTIFY_NEW_CONTENT] = updated.newContentAlerts
        prefs[Keys.NOTIFY_PERMISSION_ASKED] = updated.permissionRequested
    }

    override suspend fun setInterests(interests: List<String>): AppResult<Unit> = edit { prefs ->
        // Stored as a delimited string: DataStore Preferences has a string-set type, but it does
        // not preserve order, and the order is the whole point of an interest list.
        prefs[Keys.INTERESTS] = interests
            .filter { it.isNotBlank() && !it.contains(INTEREST_DELIMITER) }
            .joinToString(INTEREST_DELIMITER.toString())
    }

    override suspend fun setOnboardingComplete(complete: Boolean): AppResult<Unit> = edit { prefs ->
        prefs[Keys.ONBOARDING_COMPLETE] = complete
    }

    override suspend fun resetToDefaults(): AppResult<Unit> = edit { it.clear() }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit):
        AppResult<Unit> = try {
        dataStore.edit(block)
        AppResult.Success(Unit)
    } catch (io: IOException) {
        AppLog.e(TAG, "Could not write settings", io)
        AppResult.Failure(AppError.Storage("Settings could not be saved."))
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            playback = PlaybackPreferences(
                autoplayNextEpisode = this[Keys.AUTOPLAY_NEXT]
                    ?: defaults.playback.autoplayNextEpisode,
                preferredQuality = this[Keys.PREFERRED_QUALITY]
                    ?.let { name -> PreferredQuality.entries.firstOrNull { it.name == name } }
                    ?: defaults.playback.preferredQuality,
                completionThreshold = this[Keys.COMPLETION_THRESHOLD]
                    ?: defaults.playback.completionThreshold,
                playOnWifiOnly = this[Keys.WIFI_ONLY] ?: defaults.playback.playOnWifiOnly,
                seekStepSeconds = this[Keys.SEEK_STEP_SECONDS]
                    ?: defaults.playback.seekStepSeconds,
            ),
            subtitles = SubtitlePreferences(
                preferredLanguage = this[Keys.SUBTITLE_LANGUAGE],
                enabledByDefault = this[Keys.SUBTITLES_ON_BY_DEFAULT]
                    ?: defaults.subtitles.enabledByDefault,
                textSize = this[Keys.SUBTITLE_TEXT_SIZE]
                    ?.let { name -> SubtitleTextSize.entries.firstOrNull { it.name == name } }
                    ?: defaults.subtitles.textSize,
                backgroundOpacity = this[Keys.SUBTITLE_BACKGROUND_OPACITY]
                    ?: defaults.subtitles.backgroundOpacity,
                preferSdh = this[Keys.SUBTITLE_PREFER_SDH] ?: defaults.subtitles.preferSdh,
            ),
            iptv = IptvPreferences(
                allowLocalNetworkPlaylists = this[Keys.ALLOW_LOCAL_PLAYLISTS]
                    ?: defaults.iptv.allowLocalNetworkPlaylists,
                refreshOnOpen = this[Keys.IPTV_REFRESH_ON_OPEN] ?: defaults.iptv.refreshOnOpen,
                showChannelNumbers = this[Keys.SHOW_CHANNEL_NUMBERS]
                    ?: defaults.iptv.showChannelNumbers,
                lastCategory = this[Keys.IPTV_LAST_CATEGORY] ?: ChannelCategory.ALL,
            ),
            stremioAddon = StremioAddonPreferences(
                baseUrl = this[Keys.STREMIO_ADDON_URL],
                displayName = this[Keys.STREMIO_ADDON_NAME],
                authorisedByUser = this[Keys.STREMIO_ADDON_AUTHORISED] ?: false,
            ),
            notifications = NotificationPreferences(
                serviceAlerts = this[Keys.NOTIFY_SERVICE] ?: defaults.notifications.serviceAlerts,
                newContentAlerts = this[Keys.NOTIFY_NEW_CONTENT]
                    ?: defaults.notifications.newContentAlerts,
                permissionRequested = this[Keys.NOTIFY_PERMISSION_ASKED]
                    ?: defaults.notifications.permissionRequested,
            ),
            interests = this[Keys.INTERESTS]
                ?.split(INTEREST_DELIMITER)
                ?.filter { it.isNotBlank() }
                ?: emptyList(),
            onboardingComplete = this[Keys.ONBOARDING_COMPLETE] ?: false,
        )
    }

    private object Keys {
        val AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next")
        val PREFERRED_QUALITY = stringPreferencesKey("preferred_quality")
        val COMPLETION_THRESHOLD = floatPreferencesKey("completion_threshold")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val SEEK_STEP_SECONDS = intPreferencesKey("seek_step_seconds")

        val SUBTITLE_LANGUAGE = stringPreferencesKey("subtitle_language")
        val SUBTITLES_ON_BY_DEFAULT = booleanPreferencesKey("subtitles_on_by_default")
        val SUBTITLE_TEXT_SIZE = stringPreferencesKey("subtitle_text_size")
        val SUBTITLE_BACKGROUND_OPACITY = floatPreferencesKey("subtitle_background_opacity")
        val SUBTITLE_PREFER_SDH = booleanPreferencesKey("subtitle_prefer_sdh")

        val ALLOW_LOCAL_PLAYLISTS = booleanPreferencesKey("allow_local_playlists")
        val IPTV_REFRESH_ON_OPEN = booleanPreferencesKey("iptv_refresh_on_open")
        val SHOW_CHANNEL_NUMBERS = booleanPreferencesKey("show_channel_numbers")
        val IPTV_LAST_CATEGORY = stringPreferencesKey("iptv_last_category")

        val STREMIO_ADDON_URL = stringPreferencesKey("stremio_addon_url")
        val STREMIO_ADDON_NAME = stringPreferencesKey("stremio_addon_name")
        val STREMIO_ADDON_AUTHORISED = booleanPreferencesKey("stremio_addon_authorised")

        val NOTIFY_SERVICE = booleanPreferencesKey("notify_service")
        val NOTIFY_NEW_CONTENT = booleanPreferencesKey("notify_new_content")
        val NOTIFY_PERMISSION_ASKED = booleanPreferencesKey("notify_permission_asked")

        val INTERESTS = stringPreferencesKey("interests")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }
}

/** Unlikely to appear in an interest label, and validated against on write. */
private const val INTEREST_DELIMITER = '\u001F'
