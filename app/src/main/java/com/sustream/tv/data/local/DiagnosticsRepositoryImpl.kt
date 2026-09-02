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
import com.sustream.tv.domain.model.HealthState
import com.sustream.tv.domain.model.IntegrationHealth
import com.sustream.tv.domain.model.PlaylistStatus
import com.sustream.tv.domain.model.ProviderConnection
import com.sustream.tv.domain.model.ProviderId
import com.sustream.tv.domain.repository.DiagnosticsRepository
import com.sustream.tv.domain.repository.FavouritesRepository
import com.sustream.tv.domain.repository.HistoryRepository
import com.sustream.tv.domain.repository.IptvPlaylistRepository
import com.sustream.tv.domain.repository.NotificationRepository
import com.sustream.tv.domain.repository.SettingsRepository
import com.sustream.tv.domain.repository.TmdbRepository
import com.sustream.tv.domain.repository.TorBoxRepository
import com.sustream.tv.domain.repository.WatchlistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Local diagnostics: the client's share of the workbook's admin requirements.
 *
 * This is deliberately **not** an admin panel. The workbook asks for user management, provider
 * configuration and system logs (rows 54-59); shipping those in a public APK would put the
 * privileged surface and the credentials it edits on every user's device. What belongs in the client
 * is the health of *this device's own* integrations, which is what this provides. The privileged
 * surface is specified as a separate authenticated server API — see docs/ADMIN_BOUNDARY.md.
 *
 * Everything reported here is measured, not asserted. Where the app cannot know something — a
 * provider's uptime, a cache hit rate — it reports [HealthState.UNKNOWN] rather than inventing a
 * figure, which is the specific failing of the prototype's admin dashboard.
 */
class DiagnosticsRepositoryImpl(
    private val config: AppConfig,
    private val httpClientFactory: HttpClientFactory,
    private val tmdbRepository: TmdbRepository,
    private val playlistRepository: IptvPlaylistRepository,
    private val providerRepository: TorBoxRepository,
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
                add(checkProvider())
                add(checkBackend())
            }
            health.value = checks
            AppResult.Success(checks)
        }

    /**
     * Catalogue check.
     *
     * A real request, timed, rather than "is a key present". A configured key that TMDB has revoked
     * looks identical to a working one until something is actually fetched.
     */
    private suspend fun checkCatalogue(): IntegrationHealth {
        val status = config.status()
        if (status.usingMockCatalogue) {
            return IntegrationHealth(
                name = "Catalogue (TMDB)",
                state = HealthState.NOT_CONFIGURED,
                detail = "No credential and no backend configured, so the bundled demo catalogue " +
                    "is in use.",
                lastCheckedAt = timeSource.now(),
                lastLatencyMillis = null,
            )
        }

        val started = System.nanoTime()
        val result = tmdbRepository.genres(com.sustream.tv.domain.model.MediaType.MOVIE)
        val elapsed = (System.nanoTime() - started) / NANOS_PER_MILLI

        return when (result) {
            is AppResult.Success -> IntegrationHealth(
                name = "Catalogue (TMDB)",
                state = HealthState.OK,
                detail = "Authenticating with " + describe(status.tmdbAuthMode) +
                    " (" + status.tmdbKeyHint + ").",
                lastCheckedAt = timeSource.now(),
                lastLatencyMillis = elapsed,
            )

            is AppResult.Failure -> IntegrationHealth(
                name = "Catalogue (TMDB)",
                state = HealthState.FAILING,
                detail = result.error.detail ?: result.error.toString(),
                lastCheckedAt = timeSource.now(),
                lastLatencyMillis = elapsed,
            )
        }
    }

    private fun checkSecureStore(): IntegrationHealth = IntegrationHealth(
        name = "Encrypted credential store",
        state = if (credentialStore.isAvailable) HealthState.OK else HealthState.FAILING,
        detail = if (credentialStore.isAvailable) {
            // Key *names* only. Values never leave the store.
            credentialStore.storedKeyNames().size.toString() + " credential(s) stored."
        } else {
            "The device keystore is unavailable, so provider and playlist credentials cannot be " +
                "kept between sessions."
        },
        lastCheckedAt = timeSource.now(),
        lastLatencyMillis = null,
    )

    private suspend fun checkPlaylists(): List<IntegrationHealth> {
        val playlists = playlistRepository.observePlaylists().first()
        if (playlists.isEmpty()) {
            return listOf(
                IntegrationHealth(
                    name = "Live TV playlists",
                    state = HealthState.NOT_CONFIGURED,
                    detail = "No playlists have been added on this device.",
                    lastCheckedAt = timeSource.now(),
                    lastLatencyMillis = null,
                ),
            )
        }

        return playlists.map { playlist ->
            IntegrationHealth(
                name = "Playlist: " + playlist.name,
                state = when (playlist.status) {
                    PlaylistStatus.OK -> HealthState.OK
                    PlaylistStatus.NEVER_SYNCED -> HealthState.UNKNOWN
                    PlaylistStatus.PARSE_FAILED,
                    PlaylistStatus.UNREACHABLE,
                    PlaylistStatus.AUTH_FAILED,
                    -> HealthState.FAILING
                },
                detail = playlist.lastErrorDetail
                    ?: (Formatters.count(playlist.channelCount) + " channels."),
                lastCheckedAt = playlist.lastSyncedAt,
                lastLatencyMillis = null,
            )
        }
    }

    private suspend fun checkProvider(): IntegrationHealth {
        val connection = providerRepository.observeConnection().first()
        return IntegrationHealth(
            name = "Provider: " + ProviderId.TORBOX.displayName,
            state = when (connection) {
                is ProviderConnection.Connected -> HealthState.OK
                is ProviderConnection.Problem -> HealthState.FAILING
                ProviderConnection.Connecting -> HealthState.UNKNOWN
                ProviderConnection.NotConnected -> HealthState.NOT_CONFIGURED
            },
            detail = when (connection) {
                is ProviderConnection.Connected ->
                    "Connected as " + connection.account.maskedIdentifier + "."

                is ProviderConnection.Problem ->
                    connection.error.detail ?: "The provider reported a problem."

                ProviderConnection.Connecting -> "Checking…"
                ProviderConnection.NotConnected -> "Not connected."
            },
            lastCheckedAt = when (connection) {
                is ProviderConnection.Connected -> connection.checkedAt
                is ProviderConnection.Problem -> connection.checkedAt
                else -> null
            },
            lastLatencyMillis = null,
        )
    }

    private fun checkBackend(): IntegrationHealth {
        val status = config.status()
        return IntegrationHealth(
            name = "Backend",
            state = if (status.backendConfigured) HealthState.UNKNOWN else HealthState.NOT_CONFIGURED,
            detail = if (status.backendConfigured) {
                "Configured: " + status.backendHost + "."
            } else {
                "No backend configured. Accounts and cross-device sync use the in-app mock, and " +
                    "everything is kept on this device."
            },
            lastCheckedAt = timeSource.now(),
            lastLatencyMillis = null,
        )
    }

    override suspend fun cacheSizeBytes(): Long = withContext(dispatchers.io) {
        httpClientFactory.cacheSizeBytes()
    }

    override suspend fun clearCaches(): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            httpClientFactory.clearCache()
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError.Storage("The cache could not be cleared."))
        }
    }

    /**
     * Wipes everything the app holds on this device.
     *
     * Ordering matters: playlists are removed before credentials, so a failure part-way through
     * cannot leave a stored password with no playlist to own it.
     */
    override suspend fun resetAllLocalData(): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            playlistRepository.observePlaylists().first().forEach { playlist ->
                playlistRepository.deletePlaylist(playlist.id)
            }
            providerRepository.disconnect()
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

    /**
     * Human-readable report.
     *
     * Shown to the user before any sharing, and deliberately contains no credential, no full URL and
     * no email address. Diagnostics never leave the device unless the user chooses to share them.
     */
    override suspend fun buildReport(): String = withContext(dispatchers.io) {
        val status = config.status()
        val checks = health.value.ifEmpty { (runChecks() as? AppResult.Success)?.value ?: emptyList() }

        buildString {
            appendLine("SuStream diagnostics")
            appendLine("Generated: " + timeSource.now())
            appendLine("Version: " + config.versionName + " (" + config.versionCode + ")")
            appendLine("Build type: " + if (config.isDebugBuild) "debug" else "release")
            appendLine()
            appendLine("Configuration")
            appendLine("  Catalogue source: " + if (status.usingMockCatalogue) "demo" else "TMDB")
            appendLine("  TMDB auth: " + describe(status.tmdbAuthMode) + " " + status.tmdbKeyHint)
            appendLine("  Backend: " + if (status.backendConfigured) status.backendHost else "mock")
            appendLine("  Provider direct mode: " + status.providerDirectModeAvailable)
            appendLine("  Cache: " + Formatters.fileSize(httpClientFactory.cacheSizeBytes()))
            appendLine()
            appendLine("Integrations")
            checks.forEach { check ->
                appendLine("  [" + check.state + "] " + check.name)
                check.detail?.let { appendLine("      " + it) }
                check.lastLatencyMillis?.let { appendLine("      latency: " + it + " ms") }
            }
        }
    }

    private fun describe(mode: TmdbAuthMode): String = when (mode) {
        TmdbAuthMode.BEARER_TOKEN -> "a v4 read token"
        TmdbAuthMode.API_KEY_QUERY -> "a v3 API key"
        TmdbAuthMode.NONE -> "nothing"
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
