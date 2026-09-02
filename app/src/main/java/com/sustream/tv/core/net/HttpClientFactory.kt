package com.sustream.tv.core.net

import com.sustream.tv.core.config.AppConfig
import com.sustream.tv.core.config.NetworkLimits
import com.sustream.tv.core.log.Secret
import okhttp3.Cache
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Builds the app's HTTP clients.
 *
 * One shared connection pool and dispatcher (the [base] client) with per-integration clients
 * layered on top. That matters on a Fire TV Stick: each additional [OkHttpClient] built from
 * scratch brings its own thread pool and connection pool, and four of those is a measurable chunk
 * of a 1 GB device's memory.
 *
 * Every client here has `followRedirects(false)`, because redirects are followed by
 * [RedirectPolicyInterceptor] instead so the hop limit and the no-HTTPS-downgrade rule both apply.
 */
class HttpClientFactory(
    private val config: AppConfig,
    cacheDirectory: File,
) {

    /**
     * TLS policy. `RESTRICTED_TLS` is TLS 1.2+ with a modern cipher list. Real IPTV providers are
     * often on old servers, so the IPTV client relaxes to [ConnectionSpec.COMPATIBLE_TLS] and
     * cleartext — but only for user-supplied media, never for our own services.
     */
    private val appServiceConnectionSpecs = listOf(
        ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .build(),
    )

    private val cache = Cache(File(cacheDirectory, HTTP_CACHE_DIR), NetworkLimits.HTTP_CACHE_BYTES)

    /** Shared foundation: timeouts, pool, dispatcher, cache. Never used directly. */
    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(NetworkLimits.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(NetworkLimits.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(NetworkLimits.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(false)
        .followSslRedirects(false)
        .cache(cache)
        .build()

    /**
     * TMDB: authenticated, rate-limit aware, concurrency capped, HTTPS enforced, JSON only.
     */
    fun tmdb(
        readToken: () -> Secret = { config.tmdbReadToken },
        apiKey: () -> Secret = { config.tmdbApiKey },
    ): OkHttpClient = base.newBuilder()
        .connectionSpecs(appServiceConnectionSpecs)
        .addInterceptor(ConcurrencyLimitInterceptor())
        .addInterceptor(TmdbAuthInterceptor(readToken, apiKey))
        .addInterceptor(RedirectPolicyInterceptor())
        .addInterceptor(RetryInterceptor())
        .addInterceptor(ResponseSizeLimitInterceptor(NetworkLimits.MAX_JSON_BYTES))
        .addInterceptor(ContentTypeGuardInterceptor(JSON_CONTENT_TYPES))
        .apply { if (config.isDebugBuild) addInterceptor(RedactingLoggingInterceptor()) }
        .build()

    /** The SuStream backend: bearer auth supplied by the auth repository. */
    fun backend(accessToken: () -> Secret): OkHttpClient = base.newBuilder()
        .connectionSpecs(appServiceConnectionSpecs)
        .addInterceptor(BearerTokenInterceptor(accessToken))
        .addInterceptor(RedirectPolicyInterceptor())
        .addInterceptor(RetryInterceptor())
        .addInterceptor(ResponseSizeLimitInterceptor(NetworkLimits.MAX_JSON_BYTES))
        .addInterceptor(ContentTypeGuardInterceptor(JSON_CONTENT_TYPES))
        .apply { if (config.isDebugBuild) addInterceptor(RedactingLoggingInterceptor()) }
        .build()

    /**
     * Provider API (TorBox). Same policy as the backend. Only ever constructed when
     * [AppConfig.allowDirectProvider] is true, which is debug-only.
     */
    fun provider(apiKey: () -> Secret): OkHttpClient = base.newBuilder()
        .connectionSpecs(appServiceConnectionSpecs)
        .addInterceptor(BearerTokenInterceptor(apiKey))
        .addInterceptor(RedirectPolicyInterceptor())
        .addInterceptor(RetryInterceptor())
        .addInterceptor(ResponseSizeLimitInterceptor(NetworkLimits.MAX_JSON_BYTES))
        .addInterceptor(ContentTypeGuardInterceptor(JSON_CONTENT_TYPES))
        .apply { if (config.isDebugBuild) addInterceptor(RedactingLoggingInterceptor()) }
        .build()

    /**
     * IPTV playlists and EPG documents.
     *
     * Deliberately different from the clients above:
     *  - No cache. Playlists are large, change often, and caching one would keep a stale channel
     *    list around after the user pressed Refresh.
     *  - Compatible TLS and cleartext allowed, because a large share of real providers are on old
     *    or unencrypted endpoints. Whether a given URL may use cleartext is decided earlier by
     *    [UrlValidator] against the user's per-playlist acknowledgement, not here.
     *  - Longer read timeout and a much larger size ceiling.
     *  - No retry: a failed playlist fetch should surface immediately so the user can fix the URL,
     *    rather than making them wait through three attempts.
     */
    fun iptv(maxBytes: Long = NetworkLimits.MAX_PLAYLIST_BYTES): OkHttpClient = base.newBuilder()
        .cache(null)
        .readTimeout(NetworkLimits.BULK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
        .addInterceptor(RedirectPolicyInterceptor(allowHttpsDowngrade = false))
        .addInterceptor(ResponseSizeLimitInterceptor(maxBytes))
        .apply { if (config.isDebugBuild) addInterceptor(RedactingLoggingInterceptor()) }
        .build()

    /** Image loading for Coil. Cached, permissive on TLS, no auth, no retry. */
    fun images(): OkHttpClient = base.newBuilder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
        .addInterceptor(RedirectPolicyInterceptor())
        .build()

    /**
     * Media playback. Handed to Media3's OkHttp data source.
     *
     * No response-size limit: a film is legitimately gigabytes. No cache: ExoPlayer manages its
     * own buffering, and letting OkHttp cache a video stream would fill the disk. Redirects are
     * followed by OkHttp itself here, because ExoPlayer issues byte-range requests and expects
     * standard redirect behaviour on each.
     */
    fun playback(): OkHttpClient = base.newBuilder()
        .cache(null)
        .followRedirects(true)
        .followSslRedirects(true)
        .readTimeout(NetworkLimits.BULK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
        .build()

    /** Releases the shared pools. Called from `onTerminate`-equivalent teardown and from tests. */
    fun shutdown() {
        base.dispatcher.executorService.shutdown()
        base.connectionPool.evictAll()
        runCatching { cache.close() }
    }

    /** Clears the HTTP disk cache. Backs Settings -> Clear cache. */
    fun clearCache() {
        runCatching { cache.evictAll() }
    }

    fun cacheSizeBytes(): Long = runCatching { cache.size() }.getOrDefault(0L)

    private companion object {
        const val HTTP_CACHE_DIR = "http_cache"
        val JSON_CONTENT_TYPES = listOf("application/json", "application/vnd.api+json")
    }
}
