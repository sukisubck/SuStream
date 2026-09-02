package com.sustream.tv.util

import android.content.Context
import com.sustream.tv.core.config.NetworkLimits
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Builds and caches [OkHttpClient] instances.
 *
 * Each logical use-case (TMDB, IPTV/addon, backend) gets its own client so timeouts and
 * interceptors can be tuned independently without accidentally affecting other traffic.
 *
 * The shared HTTP disk cache lives in [cacheDir] and is sized by [NetworkLimits.HTTP_CACHE_BYTES].
 * Call [clearCache] when the system reports low memory (see [SuStreamApplication.onLowMemory]).
 */
class HttpClientFactory(
    private val context: Context,
    @Suppress("UnusedPrivateMember")
    private val credentialStore: CredentialStore,
) {

    private val cacheDir: File get() = File(context.cacheDir, "http")

    private val sharedCache: Cache by lazy {
        Cache(cacheDir, NetworkLimits.HTTP_CACHE_BYTES)
    }

    /** Client for TMDB API and image requests. Uses the shared disk cache. */
    fun tmdb(): OkHttpClient = base()
        .cache(sharedCache)
        .build()

    /**
     * Client for IPTV playlists, EPG documents, and addon manifest/stream requests.
     * Longer read timeout for potentially large playlist files.
     */
    fun iptv(): OkHttpClient = base()
        .readTimeout(NetworkLimits.BULK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Client for backend API requests. No disk cache — responses are short-lived auth tokens. */
    fun backend(): OkHttpClient = base().build()

    /**
     * Drops the HTTP disk cache. Called on low-memory warnings. The cache is rebuilt
     * automatically on the next request that benefits from it.
     */
    fun clearCache() {
        runCatching { sharedCache.evictAll() }
    }

    private fun base(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(NetworkLimits.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(NetworkLimits.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(NetworkLimits.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
}
