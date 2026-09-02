package com.sustream.tv.core.config

import com.sustream.tv.BuildConfig
import com.sustream.tv.core.log.Secret

interface AppConfig {
    val tmdbBaseUrl: String
    val tmdbImageFallbackBaseUrl: String
    val backendBaseUrl: String

    val tmdbReadToken: Secret
    val tmdbApiKey: Secret

    val allowDirectTmdb: Boolean

    /** Always false in production — TorBox / direct-provider path removed. */
    val allowDirectProvider: Boolean

    val hasRealBackend: Boolean
    val requireHttpsForAppServices: Boolean
    val isDebugBuild: Boolean
    val versionName: String
    val versionCode: Int

    fun status(): ConfigStatus
}

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
    BEARER_TOKEN,
    API_KEY_QUERY,
    NONE,
}

class BuildConfigAppConfig(
    private val overrideBackendBaseUrl: String? = null,
) : AppConfig {

    override val tmdbBaseUrl: String = BuildConfig.TMDB_BASE_URL
    override val tmdbImageFallbackBaseUrl: String = BuildConfig.TMDB_IMAGE_FALLBACK_BASE_URL
    override val backendBaseUrl: String = overrideBackendBaseUrl ?: BuildConfig.BACKEND_BASE_URL

    override val isDebugBuild: Boolean = BuildConfig.DEBUG
    override val versionName: String = BuildConfig.VERSION_NAME
    override val versionCode: Int = BuildConfig.VERSION_CODE

    override val tmdbReadToken: Secret =
        if (isDebugBuild) Secret(BuildConfig.DEV_TMDB_READ_TOKEN) else Secret.EMPTY

    override val tmdbApiKey: Secret =
        if (isDebugBuild) Secret(BuildConfig.DEV_TMDB_API_KEY) else Secret.EMPTY

    override val allowDirectTmdb: Boolean =
        isDebugBuild && (!tmdbReadToken.isBlank || !tmdbApiKey.isBlank)

    /** Direct-provider mode is permanently removed; always false. */
    override val allowDirectProvider: Boolean = false

    override val hasRealBackend: Boolean =
        backendBaseUrl.isNotBlank() &&
                PLACEHOLDER_BACKEND_HOSTS.none { backendBaseUrl.contains(it) }

    override val requireHttpsForAppServices: Boolean = !isDebugBuild

    override fun status(): ConfigStatus {
        val authMode = when {
            !tmdbReadToken.isBlank -> TmdbAuthMode.BEARER_TOKEN
            !tmdbApiKey.isBlank   -> TmdbAuthMode.API_KEY_QUERY
            else                   -> TmdbAuthMode.NONE
        }
        val keyHint = when (authMode) {
            TmdbAuthMode.BEARER_TOKEN  -> com.sustream.tv.core.log.Redact.tail(tmdbReadToken.reveal())
            TmdbAuthMode.API_KEY_QUERY -> com.sustream.tv.core.log.Redact.tail(tmdbApiKey.reveal())
            TmdbAuthMode.NONE          -> "<not set>"
        }
        return ConfigStatus(
            tmdbConfigured           = allowDirectTmdb || hasRealBackend,
            tmdbAuthMode             = authMode,
            tmdbKeyHint              = keyHint,
            backendConfigured        = hasRealBackend,
            backendHost              = runCatching {
                java.net.URI(backendBaseUrl).host.orEmpty()
            }.getOrDefault(""),
            providerDirectModeAvailable = false,
            providerKeyHint          = "<removed>",
            usingMockCatalogue       = !allowDirectTmdb && !hasRealBackend,
            usingMockBackend         = !hasRealBackend,
        )
    }

    private companion object {
        val PLACEHOLDER_BACKEND_HOSTS = listOf(
            "api.sustream.example",
            "example.com",
            "localhost.invalid",
        )
    }
}

object NetworkLimits {
    const val CONNECT_TIMEOUT_SECONDS      = 15L
    const val READ_TIMEOUT_SECONDS         = 30L
    const val WRITE_TIMEOUT_SECONDS        = 30L
    const val BULK_READ_TIMEOUT_SECONDS    = 60L
    const val MAX_PLAYLIST_BYTES           = 10L * 1024 * 1024
    const val MAX_EPG_BYTES                = 25L * 1024 * 1024
    const val MAX_JSON_BYTES               = 4L  * 1024 * 1024
    const val MAX_REDIRECTS                = 3
    const val MAX_CONCURRENT_TMDB_REQUESTS = 4
    const val HTTP_CACHE_BYTES             = 50L * 1024 * 1024
    const val RETRY_MAX_ATTEMPTS           = 3
    const val RETRY_BASE_DELAY_MILLIS      = 500L
    const val RETRY_MAX_DELAY_MILLIS       = 8_000L
}