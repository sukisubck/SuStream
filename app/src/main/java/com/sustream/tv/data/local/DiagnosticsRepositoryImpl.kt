package com.sustream.tv.data.local

import com.sustream.tv.core.config.AppConfig
import com.sustream.tv.core.config.TmdbAuthMode
import com.sustream.tv.core.net.HttpClientFactory
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.core.util.DispatcherProvider
import com.sustream.tv.core.util.Formatters
import com.sustream.tv.core.util.TimeSource
import com.sustream.tv.data.prefs.SecureCredentialStore
import com.sustream.tv.domain.model.AddonHealthState
import com.sustream.tv.domain.model.HealthState
import com.sustream.tv.domain.model.IntegrationHealth
import com.sustream.tv.domain.model.PlaylistStatus
import com.sustream.tv.domain.repository.AddonRepository
import com.sustream.tv.domain.repository.DiagnosticsRepository
import com.sustream.tv.domain.repository.FavouritesRepository
import com.sustream.tv.domain.repository.HistoryRepository
import com.sustream.tv.domain.repository.IptvPlaylistRepository
import com.sustream.tv.domain.repository.NotificationRepository
import com.sustream.tv.domain.repository.SettingsRepository
import com.sustream.tv.domain.repository.TmdbRepository
import com.sustream.tv.domain.repository.WatchlistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class DiagnosticsRepositoryImpl(
    private val config: AppConfig,
    private val httpClientFactory: HttpClientFactory,
    private val tmdbRepository: TmdbRepository,
    private val playlistRepository: IptvPlaylistRepository,
    private val addonRepository: AddonRepository,              // ← replaces providerRepository
    private val watchlistRepository: WatchlistRepository,
    private val historyRepository: HistoryRepository,
    private val favouritesRepository: FavouritesRepository,
    private val notificationRepository: NotificationRepository,
    private val settingsRepository: SettingsRepository,
    private val credentialStore: SecureCredentialStore,
    private val dispatchers: DispatcherProvider,
    private val timeSource: TimeSource,
) : DiagnosticsRepository {

    private val health = MutableStateFlow<List<IntegrationHealth>>(emptyList())

    override fun observeHealth(): Flow<List<IntegrationHealth>> = health.asStateFlow()

    override suspend fun runChecks(): AppResult<List<IntegrationHealth>> =
        withContext(dispatchers.io) {
            val checks = buildList {
                add(checkCatalogue())
                add(checkSecureStore())
                addAll(checkPlaylists())
                addAll(checkAddons())           // ← replaces checkProvider()
                add(checkBackend())
            }
            health.value = checks
            AppResult.Success(checks)
        }

    // checkCatalogue(), checkSecureStore(), checkPlaylists(), checkBackend() — UNCHANGED from
    // the original DiagnosticsRepositoryImpl. Copy them verbatim from the existing file.
    // Only checkProvider() is replaced, as shown below.

    private suspend fun checkAddons(): List<IntegrationHealth> {
        val addons = addonRepository.observeAddons().first()
        if (addons.isEmpty()) {
            return listOf(
                IntegrationHealth(
                    name = "Addons",
                    state = HealthState.NOT_CONFIGURED,
                    detail = "No addons have been configured on this device.",
                    lastCheckedAt = timeSource.now(),
                    lastLatencyMillis = null,
                ),
            )
        }
        return addons.map { addon ->
            IntegrationHealth(
                name = "Addon: " + addon.displayName,
                state = when (addon.lastHealthState) {
                    AddonHealthState.OK       -> HealthState.OK
                    AddonHealthState.DEGRADED -> HealthState.DEGRADED
                    AddonHealthState.FAILING  -> HealthState.FAILING
                    AddonHealthState.DISABLED -> HealthState.NOT_CONFIGURED
                    AddonHealthState.UNKNOWN  -> HealthState.UNKNOWN
                },
                detail = addon.normalisedBaseUrl,
                lastCheckedAt = addon.lastCheckedAt,
                lastLatencyMillis = null,
            )
        }
    }

    override suspend fun resetAllLocalData(): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            playlistRepository.observePlaylists().first().forEach { playlist ->
                playlistRepository.deletePlaylist(playlist.id)
            }
            addonRepository.clear()              // ← replaces providerRepository.disconnect()
            watchlistRepository.clear()
            historyRepository.clear()
            favouritesRepository.clear()
            notificationRepository.clear()
            settingsRepository.resetToDefaults()
            credentialStore.clearAll()
            httpClientFactory.clearCache()
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError.Storage("Local data could not be fully reset."))
        }
    }

    // cacheSizeBytes(), clearCaches(), buildReport() — UNCHANGED. Copy verbatim.

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}