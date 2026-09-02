package com.sustream.tv.core.di


import com.sustream.tv.data.local.AddonRepositoryImpl
import com.sustream.tv.domain.repository.AddonRepository
import com.sustream.tv.provider.htmljson.AddonManifestProbe
import com.sustream.tv.provider.htmljson.HtmlJsonAddonSourceRepository

// In the class body:

// ---- Addons (replaces Provider section) ---------------------------------

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
            HtmlJsonAddonSourceRepository(    // ← replaces StremioAddonSourceRepository
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

// ---- Notifications and diagnostics --------------------------------------

val notificationRepository: NotificationRepository by lazy {
    NotificationRepositoryImpl(
        dao = database.notificationDao(),
        playlistRepository = iptvPlaylistRepository,
        addonRepository = addonRepository,     // ← replaces providerRepository
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
        addonRepository = addonRepository,     // ← replaces providerRepository
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

// In viewModelFactory creators map, replace AddonSettingsViewModel entry with:
com.sustream.tv.presentation.addons.AddonsViewModel::class.java to {
    com.sustream.tv.presentation.addons.AddonsViewModel(
        addonRepository = addonRepository,
        probe = addonManifestProbe,
    )
},

// REMOVE from companion object:
//   const val PROVIDER_TORBOX = "torbox"

// REMOVE the torboxRetrofit and torBoxRepository lazy properties entirely.
// REMOVE the addonManifestProbe lazy that referenced provider.stremio.