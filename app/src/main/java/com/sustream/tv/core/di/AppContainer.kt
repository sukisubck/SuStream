package com.sustream.tv.core.di

import android.content.Context
import com.sustream.tv.core.config.AppConfig
import com.sustream.tv.core.config.BuildConfigAppConfig
import com.sustream.tv.core.log.AppLog
import com.sustream.tv.core.net.HttpClientFactory
import com.sustream.tv.core.net.UrlValidator
import com.sustream.tv.core.util.DefaultDispatcherProvider
import com.sustream.tv.core.util.DispatcherProvider
import com.sustream.tv.core.util.SystemTimeSource
import com.sustream.tv.core.util.TimeSource
import com.sustream.tv.data.backend.AuthRepositoryImpl
import com.sustream.tv.data.backend.BackendAuthGateway
import com.sustream.tv.data.backend.LibrarySyncGateway
import com.sustream.tv.data.backend.MockBackendGateway
import com.sustream.tv.data.backend.NoOpLibrarySyncGateway
import com.sustream.tv.data.local.DiagnosticsRepositoryImpl
import com.sustream.tv.data.local.FavouritesRepositoryImpl
import com.sustream.tv.data.local.HistoryRepositoryImpl
import com.sustream.tv.data.local.NotificationRepositoryImpl
import com.sustream.tv.data.local.SuStreamDatabase
import com.sustream.tv.data.local.WatchlistRepositoryImpl
import com.sustream.tv.data.mock.MockImageUrlBuilder
import com.sustream.tv.data.mock.MockTmdbRepository
import com.sustream.tv.data.prefs.SecureCredentialStore
import com.sustream.tv.data.prefs.SettingsRepositoryImpl
import com.sustream.tv.data.tmdb.TmdbApi
import com.sustream.tv.data.tmdb.TmdbImageUrlBuilder
import com.sustream.tv.data.tmdb.TmdbRepositoryImpl
import com.sustream.tv.domain.repository.AuthRepository
import com.sustream.tv.domain.repository.AuthorisedSourceRepository
import com.sustream.tv.domain.repository.DiagnosticsRepository
import com.sustream.tv.domain.repository.EpgRepository
import com.sustream.tv.domain.repository.FavouritesRepository
import com.sustream.tv.domain.repository.HistoryRepository
import com.sustream.tv.domain.repository.ImageUrlBuilder
import com.sustream.tv.domain.repository.IptvPlaylistRepository
import com.sustream.tv.domain.repository.NotificationRepository
import com.sustream.tv.domain.repository.PlaybackRepository
import com.sustream.tv.domain.repository.SettingsRepository
import com.sustream.tv.domain.repository.TmdbRepository
import com.sustream.tv.domain.repository.TorBoxRepository
import com.sustream.tv.domain.repository.WatchlistRepository
import com.sustream.tv.iptv.IptvPlaylistRepositoryImpl
import com.sustream.tv.iptv.PlaylistFetcher
import com.sustream.tv.iptv.epg.EpgRepositoryImpl
import com.sustream.tv.iptv.xtream.XtreamClient
import com.sustream.tv.playback.PlaybackRepositoryImpl
import com.sustream.tv.playback.PlayerManager
import com.sustream.tv.provider.source.CompositeAuthorisedSourceRepository
import com.sustream.tv.provider.source.IptvBackedSourceRepository
import com.sustream.tv.provider.source.MockAuthorisedSourceRepository
import com.sustream.tv.provider.stremio.StremioAddonSourceRepository
import com.sustream.tv.provider.torbox.MockTorBoxRepository
import com.sustream.tv.provider.torbox.TorBoxApi
import com.sustream.tv.provider.torbox.TorBoxRepositoryImpl
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private const val TAG = "DI"

/**
 * The application object graph, assembled by hand.
 *
 * ## Why not Hilt
 *
 * Three reasons, in order of weight:
 *
 *  1. **Build risk.** Hilt adds a Gradle plugin performing bytecode transformation plus an
 *     annotation processor, on an AGP 9 / Kotlin 2.2 / JDK 25 toolchain that is very new. Room's KSP
 *     already needed an escape hatch here (see `gradle.properties`); adding a second codegen layer
 *     multiplies that risk for no functional gain.
 *  2. **Testability.** Every class in this app takes its dependencies through its constructor, so a
 *     test builds exactly the object it needs with no framework, no test runner requirement and no
 *     module overriding.
 *  3. **Size.** The graph is around forty objects and fits in one readable file. Hilt earns its
 *     keep on graphs with many scopes and many modules; this is not one.
 *
 * ## Laziness
 *
 * Everything is `by lazy`. On a Fire TV Stick, cold start is the metric that matters most, and
 * eagerly constructing the database, the OkHttp clients and the player at `Application.onCreate`
 * would add measurable time before the first frame. `lazy` is thread-safe by default, which is what
 * we want given that view models are created off the main thread.
 *
 * ## Selecting implementations
 *
 * The mock-or-real choice is made once, here, from [AppConfig]. A fresh checkout with no credentials
 * gets a fully navigable app on demo data rather than an empty screen; adding a token to
 * `local.properties` switches it to live TMDB with no code change.
 */
class AppContainer(
    private val applicationContext: Context,
    val config: AppConfig = BuildConfigAppConfig(),
    val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
    val timeSource: TimeSource = SystemTimeSource,
) {

    // ---- Foundations --------------------------------------------------------

    val urlValidator: UrlValidator by lazy {
        UrlValidator(requireHttpsForAppServices = config.requireHttpsForAppServices)
    }

    val httpClientFactory: HttpClientFactory by lazy {
        HttpClientFactory(config = config, cacheDirectory = applicationContext.cacheDir)
    }

    /**
     * Shared JSON configuration.
     *
     * `ignoreUnknownKeys` because upstream APIs add fields without notice, and a new field must not
     * break the client. `explicitNulls = false` so a request body omits nulls rather than sending
     * them, which some backends reject.
     */
    val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            coerceInputValues = true
        }
    }

    private val database: SuStreamDatabase by lazy {
        SuStreamDatabase.create(applicationContext)
    }

    val credentialStore: SecureCredentialStore by lazy {
        SecureCredentialStore(applicationContext)
    }

    // ---- Settings -----------------------------------------------------------

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(applicationContext)
    }

    // ---- Catalogue ----------------------------------------------------------

    private val tmdbRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(config.tmdbBaseUrl)
            .client(httpClientFactory.tmdb())
            .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE.toMediaType()))
            .build()
    }

    private val tmdbImageUrlBuilder: TmdbImageUrlBuilder by lazy {
        TmdbImageUrlBuilder(fallbackBaseUrl = config.tmdbImageFallbackBaseUrl)
    }

    /**
     * Live TMDB when a credential is configured, the bundled demo catalogue otherwise.
     *
     * This is the switch that makes a fresh checkout usable. It is logged at startup so nobody
     * wonders why the catalogue looks like fixtures.
     */
    val tmdbRepository: TmdbRepository by lazy {
        if (config.allowDirectTmdb) {
            AppLog.i(TAG, "Catalogue: live TMDB")
            TmdbRepositoryImpl(
                api = tmdbRetrofit.create(TmdbApi::class.java),
                imageUrlBuilder = tmdbImageUrlBuilder,
                dispatchers = dispatchers,
                timeSource = timeSource,
            )
        } else {
            AppLog.i(TAG, "Catalogue: bundled demo data (no TMDB credential configured)")
            MockTmdbRepository(artificialDelayMillis = MOCK_LATENCY_MILLIS)
        }
    }

    val imageUrlBuilder: ImageUrlBuilder by lazy {
        if (config.allowDirectTmdb) tmdbImageUrlBuilder else MockImageUrlBuilder()
    }

    // ---- Backend ------------------------------------------------------------

    /**
     * One instance serves as both gateways.
     *
     * The mock backend implements auth and library sync, and they share state — a token issued by
     * one has to be recognised by the other. Two instances would have two disjoint sets of accounts.
     */
    private val mockBackend: MockBackendGateway by lazy { MockBackendGateway(timeSource) }

    val backendAuthGateway: BackendAuthGateway by lazy {
        if (config.hasRealBackend) {
            // TODO(backend): construct the Retrofit-backed gateway against
            //  backend-contract/openapi.yaml once the service exists. Until then the mock is used
            //  even if a URL is set, so the app never fires requests at an unimplemented host.
            AppLog.i(TAG, "Backend URL is set but no client is implemented yet; using the mock")
            mockBackend
        } else {
            mockBackend
        }
    }

    val librarySyncGateway: LibrarySyncGateway by lazy {
        if (config.hasRealBackend) mockBackend else NoOpLibrarySyncGateway
    }

    // ---- Library ------------------------------------------------------------

    val watchlistRepository: WatchlistRepository by lazy {
        WatchlistRepositoryImpl(
            dao = database.libraryDao(),
            syncGateway = librarySyncGateway,
            dispatchers = dispatchers,
            timeSource = timeSource,
        )
    }

    val historyRepository: HistoryRepository by lazy {
        HistoryRepositoryImpl(
            dao = database.libraryDao(),
            settingsRepository = settingsRepository,
            syncGateway = librarySyncGateway,
            dispatchers = dispatchers,
            timeSource = timeSource,
        )
    }

    val favouritesRepository: FavouritesRepository by lazy {
        FavouritesRepositoryImpl(
            dao = database.libraryDao(),
            dispatchers = dispatchers,
            timeSource = timeSource,
        )
    }

    val authRepository: AuthRepository by lazy {
        AuthRepositoryImpl(
            gateway = backendAuthGateway,
            credentialStore = credentialStore,
            watchlistRepository = watchlistRepository,
            historyRepository = historyRepository,
            settingsRepository = settingsRepository,
            dispatchers = dispatchers,
            timeSource = timeSource,
        )
    }

    // ---- IPTV ---------------------------------------------------------------

    private val playlistFetcher: PlaylistFetcher by lazy {
        PlaylistFetcher(
            httpClient = httpClientFactory.iptv(),
            contentResolver = applicationContext.contentResolver,
            urlValidator = urlValidator,
            dispatchers = dispatchers,
        )
    }

    private val xtreamClient: XtreamClient by lazy {
        XtreamClient(
            httpClient = httpClientFactory.iptv(),
            urlValidator = urlValidator,
        )
    }

    val iptvPlaylistRepository: IptvPlaylistRepository by lazy {
        IptvPlaylistRepositoryImpl(
            iptvDao = database.iptvDao(),
            libraryDao = database.libraryDao(),
            fetcher = playlistFetcher,
            xtreamClient = xtreamClient,
            credentialStore = credentialStore,
            settingsRepository = settingsRepository,
            urlValidator = urlValidator,
            probeClient = httpClientFactory.iptv(),
            dispatchers = dispatchers,
            timeSource = timeSource,
        )
    }

    val epgRepository: EpgRepository by lazy {
        EpgRepositoryImpl(
            iptvDao = database.iptvDao(),
            // A larger ceiling than a playlist: XMLTV feeds are an order of magnitude bigger.
            fetcher = PlaylistFetcher(
                httpClient = httpClientFactory.iptv(
                    maxBytes = com.sustream.tv.core.config.NetworkLimits.MAX_EPG_BYTES,
                ),
                contentResolver = applicationContext.contentResolver,
                urlValidator = urlValidator,
                dispatchers = dispatchers,
            ),
            settingsRepository = settingsRepository,
            dispatchers = dispatchers,
            timeSource = timeSource,
        )
    }

    // ---- Provider -----------------------------------------------------------

    private val torboxRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(config.torboxBaseUrl)
            .client(
                httpClientFactory.provider {
                    credentialStore.providerApiKey(PROVIDER_TORBOX)
                        ?: config.torboxApiKeyForDirectMode
                },
            )
            .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE.toMediaType()))
            .build()
    }

    /**
     * The real provider client only in a debug build with a key configured.
     *
     * In release the credential is the backend's, so the direct client cannot be constructed and the
     * mock is used until the backend-mediated path is implemented.
     */
    val torBoxRepository: TorBoxRepository by lazy {
        if (config.allowDirectProvider) {
            AppLog.i(TAG, "Provider: TorBox direct mode (debug build)")
            TorBoxRepositoryImpl(
                api = torboxRetrofit.create(TorBoxApi::class.java),
                credentialStore = credentialStore,
                urlValidator = urlValidator,
                dispatchers = dispatchers,
                timeSource = timeSource,
            )
        } else {
            AppLog.i(TAG, "Provider: demo implementation")
            MockTorBoxRepository(timeSource)
        }
    }

    // ---- Source discovery ---------------------------------------------------

    /**
     * Source adapters, in preference order.
     *
     * The user's own playlists come first, because they are a real authorisation. The demo adapter is
     * last and is only reached when nothing real is configured — [CompositeAuthorisedSourceRepository]
     * also sorts demo sources to the bottom of the list, so a demo entry is never pre-selected above
     * a real one.
     *
     * There is no torrent, DDL or index adapter, and adding one would violate the contract on
     * [AuthorisedSourceRepository]. See docs/DEFERRED_AND_RESTRICTED.md.
     */
    val authorisedSourceRepository: AuthorisedSourceRepository by lazy {
        CompositeAuthorisedSourceRepository(
            adapters = listOf(
                IptvBackedSourceRepository(
                    playlistRepository = iptvPlaylistRepository,
                    settingsRepository = settingsRepository,
                    urlValidator = urlValidator,
                ),
                StremioAddonSourceRepository(
                    settingsRepository = settingsRepository,
                    urlValidator = urlValidator,
                    httpClient = httpClientFactory.iptv(),
                    json = json,
                ),
                MockAuthorisedSourceRepository(
                    artificialDelayMillis = MOCK_LATENCY_MILLIS,
                    // Demo sources appear only when nothing real could serve the title; the
                    // composite's ordering keeps them below anything real.
                    configured = true,
                ),
            ),
        )
    }

    // ---- Playback -----------------------------------------------------------

    val playbackRepository: PlaybackRepository by lazy {
        PlaybackRepositoryImpl(
            sourceRepository = authorisedSourceRepository,
            tmdbRepository = tmdbRepository,
            dispatchers = dispatchers,
            timeSource = timeSource,
        )
    }

    /**
     * The single player instance.
     *
     * Owned by the container rather than by a view model, so it survives navigation between the
     * player screen and a track-selection sheet without being torn down and rebuilt — which on a
     * 1 GB device is both slow and visible.
     */
    val playerManager: PlayerManager by lazy {
        PlayerManager(
            context = applicationContext,
            playbackHttpClient = httpClientFactory.playback(),
        )
    }

    // ---- Notifications and diagnostics --------------------------------------

    val notificationRepository: NotificationRepository by lazy {
        NotificationRepositoryImpl(
            dao = database.notificationDao(),
            playlistRepository = iptvPlaylistRepository,
            providerRepository = torBoxRepository,
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
            providerRepository = torBoxRepository,
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

    /**
     * One-shot carrier for the source the user picked in the sources sheet.
     *
     * Lives on the container rather than in a route, because a source carries a provider-specific
     * resolution key that cannot be serialised into a URL. See [PlaybackHandoff].
     */
    val playbackHandoff: PlaybackHandoff by lazy { PlaybackHandoff() }

    /**
     * Verifies an addon manifest before its URL is saved.
     *
     * Deliberately built on the same HTTP client the addon adapter uses, so a manifest that probes
     * successfully is reachable under exactly the timeouts, redirect limits and TLS policy that
     * playback will later apply.
     */
    val addonManifestProbe: com.sustream.tv.provider.stremio.AddonManifestProbe by lazy {
        com.sustream.tv.provider.stremio.AddonManifestProbe(
            httpClient = httpClientFactory.iptv(),
            urlValidator = urlValidator,
            json = json,
            dispatchers = dispatchers,
        )
    }

    // ---- View models --------------------------------------------------------

    /**
     * The view-model registry.
     *
     * One entry per view model, each a lambda over this container. Built once and cached, because
     * `viewModel()` calls this on every composition of a screen and rebuilding the map each time
     * would allocate on every recomposition.
     *
     * A missing entry fails loudly with the class name (see [SuStreamViewModelFactory]), which is a
     * far better failure than the reflective `NoSuchMethodException` the default factory produces
     * for a view model with constructor parameters.
     */
    private val viewModelFactory: SuStreamViewModelFactory by lazy {
        SuStreamViewModelFactory(
            container = this,
            creators = mapOf(
                com.sustream.tv.presentation.settings.AddonSettingsViewModel::class.java to {
                    com.sustream.tv.presentation.settings.AddonSettingsViewModel(
                        settingsRepository = settingsRepository,
                        probe = addonManifestProbe,
                    )
                },
                com.sustream.tv.presentation.home.HomeViewModel::class.java to {
                    com.sustream.tv.presentation.home.HomeViewModel(
                        tmdbRepository = tmdbRepository,
                        watchlistRepository = watchlistRepository,
                        historyRepository = historyRepository,
                        settingsRepository = settingsRepository,
                        notificationRepository = notificationRepository,
                    )
                },
                com.sustream.tv.presentation.live.LiveTvViewModel::class.java to {
                    com.sustream.tv.presentation.live.LiveTvViewModel(
                        playlistRepository = iptvPlaylistRepository,
                        epgRepository = epgRepository,
                    )
                },
            ),
        )
    }

    fun viewModelFactory(): SuStreamViewModelFactory = viewModelFactory

    /**
     * Factory for a view model that needs a runtime argument.
     *
     * A screen keyed by a media id cannot come from the shared registry, because the registry maps a
     * class to a no-argument lambda. A per-argument factory is the simplest correct answer and keeps
     * the id out of the view model's mutable state, so it cannot change under the screen.
     */
    fun detailsViewModelFactory(
        mediaId: com.sustream.tv.domain.model.MediaId,
    ): androidx.lifecycle.ViewModelProvider.Factory =
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T = com.sustream.tv.presentation.details.DetailsViewModel(
                mediaId = mediaId,
                tmdbRepository = tmdbRepository,
                sourceRepository = authorisedSourceRepository,
                watchlistRepository = watchlistRepository,
                historyRepository = historyRepository,
            ) as T
        }

    /**
     * Factory for the player, parameterised by what the user asked to play.
     *
     * @param preselectedSource the choice made in the sources sheet, when the player was reached
     *   that way. Null on every other path, in which case the player runs its own discovery.
     */
    fun playerViewModelFactory(
        request: com.sustream.tv.domain.model.PlaybackRequest,
        preselectedSource: com.sustream.tv.domain.model.PlayableSource? = null,
    ): androidx.lifecycle.ViewModelProvider.Factory =
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T = com.sustream.tv.presentation.player.PlayerViewModel(
                request = request,
                preselectedSource = preselectedSource,
                playerManager = playerManager,
                playbackRepository = playbackRepository,
                sourceRepository = authorisedSourceRepository,
                tmdbRepository = tmdbRepository,
                historyRepository = historyRepository,
                settingsRepository = settingsRepository,
                iptvPlaylistRepository = iptvPlaylistRepository,
                timeSource = timeSource,
            ) as T
        }

    /** Factory for the catalogue grid, which is parameterised by media type. */
    fun catalogueViewModelFactory(
        type: com.sustream.tv.domain.model.MediaType,
    ): androidx.lifecycle.ViewModelProvider.Factory =
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T = com.sustream.tv.presentation.catalogue.CatalogueViewModel(
                mediaType = type,
                tmdbRepository = tmdbRepository,
                watchlistRepository = watchlistRepository,
            ) as T
        }

    /** Releases the player and the shared connection pools. Called from process teardown. */
    fun shutdown() {
        playerManager.release()
        httpClientFactory.shutdown()
    }

    private companion object {
        const val JSON_MEDIA_TYPE = "application/json"
        const val PROVIDER_TORBOX = "torbox"

        /**
         * Latency added to demo repositories so loading and skeleton states are visible while
         * developing against fixtures. Small enough not to be an annoyance.
         */
        const val MOCK_LATENCY_MILLIS = 350L
    }
}
