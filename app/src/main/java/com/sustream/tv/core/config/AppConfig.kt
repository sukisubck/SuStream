package com.sustream.tv.core.config

import com.sustream.tv.BuildConfig
import com.sustream.tv.core.log.Secret

/**
 * Single source of truth for "what is this build allowed to talk to, and with what".
 *
 * The important rule enforced here: **release builds never read the `DEV_*` BuildConfig fields.**
 * Those fields exist so a developer can point a debug build straight at TMDB or a provider without
 * standing up the backend first. In a release build the app has no embedded credentials at all and
 * obtains everything through the backend, which is the only place a production secret lives.
 *
 * If a value is missing, the app does not crash and does not silently misbehave: it degrades to
 * mock data and reports the reason through [ConfigStatus] so Settings -> Diagnostics can explain
 * it to the user. See docs/SECURITY.md section 1.
 */
interface AppConfig {
    val tmdbBaseUrl: String
    val tmdbImageFallbackBaseUrl: String
    val backendBaseUrl: String

    /** TMDB v4 read token, for debug builds talking to TMDB directly. */
    val tmdbReadToken: Secret

    /** TMDB v3 key. Only used if [tmdbReadToken] is absent and v3 query auth is the fallback. */
    val tmdbApiKey: Secret

    /**
     * Provider key for the debug-only direct mode. Empty in release: the backend holds the key and
     * the client calls the backend's `providers/torbox` endpoints instead.
     */

    /** True when the client may call TMDB directly rather than through the backend. */
    val allowDirectTmdb: Boolean

    /** True when the client may call the provider API directly. Debug only, by construction. */
    val allowDirectProvider: Boolean

    /** False when the placeholder backend URL is still in place, so the mock backend is used. */
    val hasRealBackend: Boolean

    /** Release builds refuse cleartext to app services; debug builds allow a local proxy. */
    val requireHttpsForAppServices: Boolean

    val isDebugBuild: Boolean
    val versionName: String
    val versionCode: Int

    fun status(): ConfigStatus
}

/**
 * What is and is not configured, for the Diagnostics screen. Deliberately carries no secret —
 * only whether one is present, and a masked tail so the user can tell *which* key is in use.
 */
data class ConfigStatus(
    val tmdbConfigured: Boolean,
    val tmdbAuthMode: TmdbAuthMode,
    val tmdbKeyHint: String,
    val backendConfigured: Boolean,
    val backendHost: String,
    val providerDirectModeAvailable: Boolean,
    val providerKeyHint: String,
    val usingMockCatalogue: Boolean,
    val usingMockBackend: Boolean,
)

enum class TmdbAuthMode {
    /** v4 `Authorization: Bearer <token>`. Preferred. */
    BEARER_TOKEN,

    /** v3 `?api_key=` query parameter. Fallback only. */
    API_KEY_QUERY,

    /** Nothing configured; the app runs on the bundled mock catalogue. */
    NONE,
}

/**
 * The production implementation, driven by generated BuildConfig fields.
 *
 * @param overrideBackendBaseUrl lets instrumentation tests point the client at a MockWebServer
 *   without touching the build.
 */
class BuildConfigAppConfig(
    private val overrideBackendBaseUrl: String? = null,
) : AppConfig {

    override val tmdbBaseUrl: String = BuildConfig.TMDB_BASE_URL
    override val tmdbImageFallbackBaseUrl: String = BuildConfig.TMDB_IMAGE_FALLBACK_BASE_URL
    override val torboxBaseUrl: String = BuildConfig.TORBOX_BASE_URL
    override val backendBaseUrl: String = overrideBackendBaseUrl ?: BuildConfig.BACKEND_BASE_URL

    override val isDebugBuild: Boolean = BuildConfig.DEBUG
    override val versionName: String = BuildConfig.VERSION_NAME
    override val versionCode: Int = BuildConfig.VERSION_CODE

    // ---- Credentials --------------------------------------------------------
    // The `if (isDebugBuild)` guards are the whole point of this class. Even if a credential were
    // accidentally baked into a release build, nothing would read it.

    override val tmdbReadToken: Secret =
        if (isDebugBuild) Secret(BuildConfig.DEV_TMDB_READ_TOKEN) else Secret.EMPTY

    override val tmdbApiKey: Secret =
        if (isDebugBuild) Secret(BuildConfig.DEV_TMDB_API_KEY) else Secret.EMPTY

    override val allowDirectTmdb: Boolean =
        isDebugBuild && (!tmdbReadToken.isBlank || !tmdbApiKey.isBlank)

    override val hasRealBackend: Boolean =
        backendBaseUrl.isNotBlank() && PLACEHOLDER_BACKEND_HOSTS.none { backendBaseUrl.contains(it) }

    override val requireHttpsForAppServices: Boolean = !isDebugBuild

    override fun status(): ConfigStatus {
        val authMode = when {
            !tmdbReadToken.isBlank -> TmdbAuthMode.BEARER_TOKEN
            !tmdbApiKey.isBlank -> TmdbAuthMode.API_KEY_QUERY
            hasRealBackend -> TmdbAuthMode.NONE // backend proxies TMDB; the client holds nothing
            else -> TmdbAuthMode.NONE
        }
        val keyHint = when (authMode) {
            TmdbAuthMode.BEARER_TOKEN -> com.sustream.tv.core.log.Redact.tail(tmdbReadToken.reveal())
            TmdbAuthMode.API_KEY_QUERY -> com.sustream.tv.core.log.Redact.tail(tmdbApiKey.reveal())
            TmdbAuthMode.NONE -> "<not set>"
        }
        return ConfigStatus(
            tmdbConfigured = allowDirectTmdb || hasRealBackend,
            tmdbAuthMode = authMode,
            tmdbKeyHint = keyHint,
            backendConfigured = hasRealBackend,
            backendHost = runCatching {
                java.net.URI(backendBaseUrl).host.orEmpty()
            }.getOrDefault(""),
            providerDirectModeAvailable = allowDirectProvider,
            providerKeyHint = com.sustream.tv.core.log.Redact.tail(
                torboxApiKeyForDirectMode.reveal(),
            ),
            usingMockCatalogue = !allowDirectTmdb && !hasRealBackend,
            usingMockBackend = !hasRealBackend,
        )
    }

    private companion object {
        /**
         * Hosts that mean "nobody has configured a backend yet". Matching any of these puts the
         * app on the in-app mock backend rather than firing requests at a domain we do not own.
         */
        val PLACEHOLDER_BACKEND_HOSTS = listOf(
            "api.sustream.example",
            "example.com",
            "localhost.invalid",
        )
    }
}

/** Network tunables. Kept together so limits are reviewable in one place. */
object NetworkLimits {
    const val CONNECT_TIMEOUT_SECONDS = 15L
    const val READ_TIMEOUT_SECONDS = 30L
    const val WRITE_TIMEOUT_SECONDS = 30L
    /** Playlists and EPG documents can be genuinely large, so they get a longer read window. */
    const val BULK_READ_TIMEOUT_SECONDS = 60L

    /** Ceiling on a downloaded playlist. 10 MB is ~200k channel lines: far beyond any real list. */
    const val MAX_PLAYLIST_BYTES = 10L * 1024 * 1024

    /** EPG documents are much larger than playlists; XMLTV for 500 channels runs to tens of MB. */
    const val MAX_EPG_BYTES = 25L * 1024 * 1024

    /** Cap on any JSON API response, to bound memory on a 1 GB Fire TV Stick. */
    const val MAX_JSON_BYTES = 4L * 1024 * 1024

    const val MAX_REDIRECTS = 3

    /** TMDB has no published rate limit any more, so we self-limit rather than guess theirs. */
    const val MAX_CONCURRENT_TMDB_REQUESTS = 4

    /** OkHttp disk cache for TMDB JSON and images. */
    const val HTTP_CACHE_BYTES = 50L * 1024 * 1024

    const val RETRY_MAX_ATTEMPTS = 3
    const val RETRY_BASE_DELAY_MILLIS = 500L
    const val RETRY_MAX_DELAY_MILLIS = 8_000L
}
