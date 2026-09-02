package com.sustream.tv.core.di

import android.content.Context
import com.sustream.tv.BuildConfig
import com.sustream.tv.data.local.AddonRepositoryImpl
import com.sustream.tv.data.local.AppDatabase
import com.sustream.tv.data.local.IptvPlaylistRepositoryImpl
import com.sustream.tv.data.local.SettingsRepositoryImpl
import com.sustream.tv.data.remote.TmdbRepositoryImpl
import com.sustream.tv.data.remote.auth.AuthRepositoryImpl
import com.sustream.tv.data.remote.auth.MockBackendGateway
import com.sustream.tv.data.repository.CompositeAuthorisedSourceRepository
import com.sustream.tv.data.repository.DiagnosticsRepositoryImpl
import com.sustream.tv.data.repository.FavouritesRepositoryImpl
import com.sustream.tv.data.repository.HistoryRepositoryImpl
import com.sustream.tv.data.repository.MockAuthorisedSourceRepository
import com.sustream.tv.data.repository.NotificationRepositoryImpl
import com.sustream.tv.data.repository.WatchlistRepositoryImpl
import com.sustream.tv.data.repository.IptvBackedSourceRepository
import com.sustream.tv.domain.repository.AddonRepository
import com.sustream.tv.domain.repository.AuthRepository
import com.sustream.tv.domain.repository.AuthorisedSourceRepository
import com.sustream.tv.domain.repository.DiagnosticsRepository
import com.sustream.tv.domain.repository.FavouritesRepository
import com.sustream.tv.domain.repository.HistoryRepository
import com.sustream.tv.domain.repository.IptvPlaylistRepository
import com.sustream.tv.domain.repository.NotificationRepository
import com.sustream.tv.domain.repository.SettingsRepository
import com.sustream.tv.domain.repository.TmdbRepository
import com.sustream.tv.domain.repository.WatchlistRepository
import com.sustream.tv.domain.usecase.PlaybackHandoff
import com.sustream.tv.provider.htmljson.AddonManifestProbe
import com.sustream.tv.provider.htmljson.HtmlJsonAddonSourceRepository
import com.sustream.tv.util.AppDispatchers
import com.sustream.tv.util.CredentialStore
import com.sustream.tv.util.HttpClientFactory
import com.sustream.tv.util.TimeSource
import com.sustream.tv.util.UrlValidator
import kotlinx.serialization.json.Json

/**
 * Hand-assembled dependency container.
 *
 * All properties are `lazy` so the graph is built on first access and only
 * the subset actually used by the current screen is instantiated. No Hilt,
 * no Dagger — the compile-time graph is small enough to own manually.
 *
 * TorBox / provider-cloud integration has been removed. Sources are:
 *   1. IPTV playlists (M3U/Xtream)
 *   2. HtmlJson addons (Stremio-compatible manifest + stream endpoints)
 *   3. Mock source (dev/test, always present but configurable)
 */
class AppContainer(private val applicationContext: Context) {

    // ---- Core utilities -----------------------------------------------------

    val dispatchers: AppDispatchers by lazy { AppDispatchers() }

    val timeSource: TimeSource by lazy { TimeSource.System }

    val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }

    val urlValidator: UrlValidator by lazy { UrlValidator() }

    val credentialStore: CredentialStore by lazy {
        CredentialStore(applicationContext)
    }

    val httpClientFactory: HttpClientFactory by lazy {
        HttpClientFactory(
            context = applicationContext,
            credentialStore = credentialStore,
        )
    }

    val config: AppConfig by lazy {
        AppConfig(
            tmdbApiKey = BuildConfig.TMDB_API_KEY,
            tmdbBaseUrl = BuildConfig.TMDB_BASE_URL,
            backendBaseUrl = BuildConfig.BACKEND_BASE_URL,
        )
    }

    // ---- Persistence --------------------------------------------------------

    val database: AppDatabase by lazy {
        AppDatabase.build(applicationContext)
    }

    // ---- Settings -----------------------------------------------------------

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(
            dataStore = applicationContext,
            dispatchers = dispatchers,
        )
    }

    // ---- Auth ---------------------------------------------------------------

    val authRepository: AuthRepository by lazy {
        AuthRepositoryImpl(
            gateway = MockBackendGateway(
                credentialStore = credentialStore,
                dispatchers = dispatchers,
            ),
            credentialStore = credentialStore,
            dispatchers = dispatchers,
        )
    }

    // ---- TMDB ---------------------------------------------------------------

    val tmdbRepository: TmdbRepository by lazy {
        TmdbRepositoryImpl(
            httpClient = httpClientFactory.tmdb(),
            json = json,
            config = config,
            snapshotDao = database.mediaSnapshotDao(),
            dispatchers = dispatchers,
            timeSource = timeSource,
        )
    }

    // ---- IPTV playlists -----------------------------------------------------

    val iptvPlaylistRepository: IptvPlaylistRepository by lazy {
        IptvPlaylistRepositoryImpl(
            playlistDao = database.playlistDao(),
            channelDao = database.channelDao(),
            epgDao = database.epgDao(),
            httpClient = httpClientFactory.iptv(),
            json = json,
            urlValidator = urlValidator,
            dispatchers = dispatchers,
            timeSource = timeSource,
        )
    }

    // ---- Addons (replaces Provider / TorBox section) ------------------------

    val addonRepository: AddonRepository by lazy {
        AddonRepositoryImpl(
            dao = database.addonDao(),
            dispatchers = dispatchers,
            timeSource = timeSource,
        )
    }

    val addonManifestProbe: AddonManifestProbe by lazy {
        AddonManifestProbe(
            httpClient = httpClientFactory.iptv(),
            urlValidator = urlValidator,
            json = json,
            dispatchers = dispatchers,
        )
    }

    // ---- Source discovery ---------------------------------------------------

    val authorisedSourceRepository: AuthorisedSourceRepository by lazy {
        CompositeAuthorisedSourceRepository(
            adapters = listOf(
                IptvBackedSourceRepository(
                    playlistRepository = iptvPlaylistRepository,
                    settingsRepository = settingsRepository,
                    urlValidator = urlValidator,
                ),
                HtmlJsonAddonSourceRepository(
                    addonRepository = addonRepository,
                    urlValidator = urlValidator,
                    httpClient = httpClientFactory.iptv(),
                    json = json,
                ),
                MockAuthorisedSourceRepository(
                    artificialDelayMillis = MOCK_LATENCY_MILLIS,
                    configured = true,
                ),
            ),
        )
    }

    // ---- Library ------------------------------------------------------------

    val watchlistRepository: WatchlistRepository by lazy {
        WatchlistRepositoryImpl(
            watchlistDao = database.watchlistDao(),
            snapshotDao = database.mediaSnapshotDao(),
            dispatchers = dispatchers,
            timeSource = timeSource,
        )
    }

    val historyRepository: HistoryRepository by lazy {
        HistoryRepositoryImpl(
            progressDao = database.watchProgressDao(),
            snapshotDao = database.mediaSnapshotDao(),
            dispatchers = dispatchers,
            timeSource = timeSource,
        )
    }

    val favouritesRepository: FavouritesRepository by lazy {
        FavouritesRepositoryImpl(
            favouriteDao = database.favouriteDao(),
            dispatchers = dispatchers,
            timeSource = timeSource,
        )
    }

    // ---- Notifications and diagnostics --------------------------------------

    val notificationRepository: NotificationRepository by lazy {
        NotificationRepositoryImpl(
            dao = database.notificationDao(),
            playlistRepository = iptvPlaylistRepository,
            addonRepository = addonRepository,
            settingsRepository = settingsRepository,
            dispatchers = dispatchers,
            timeSource = timeSource,
        )
    }

    val diagnosticsRepository: DiagnosticsRepository by lazy {
        DiagnosticsRepositoryImpl(
            config = config,
            httpClientFactory = httpClientFactory,
            tmdbRepository = tmdbRepository,
            playlistRepository = iptvPlaylistRepository,
            addonRepository = addonRepository,
            watchlistRepository = watchlistRepository,
            historyRepository = historyRepository,
            favouritesRepository = favouritesRepository,
            notificationRepository = notificationRepository,
            settingsRepository = settingsRepository,
            credentialStore = credentialStore,
            dispatchers = dispatchers,
            timeSource = timeSource,
        )
    }

    // ---- Playback handoff ---------------------------------------------------

    /** One-shot channel: DetailsScreen deposits a resolved source; PlayerScreen collects it. */
    val playbackHandoff: PlaybackHandoff by lazy { PlaybackHandoff() }

    // ---- ViewModel factory --------------------------------------------------

    val viewModelFactory: SuStreamViewModelFactory by lazy {
        SuStreamViewModelFactory(
            container = this,
        )
    }

    companion object {
        private const val MOCK_LATENCY_MILLIS = 400L
    }
}
