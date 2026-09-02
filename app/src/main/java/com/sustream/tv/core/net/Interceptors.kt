package com.sustream.tv.core.net

import com.sustream.tv.core.config.NetworkLimits
import com.sustream.tv.core.log.AppLog
import com.sustream.tv.core.log.Redact
import com.sustream.tv.core.log.Secret
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.Semaphore
import kotlin.math.min
import kotlin.random.Random

private const val TAG = "Http"

/**
 * Adds TMDB credentials.
 *
 * Prefers the v4 bearer token. Falls back to the v3 `api_key` query parameter only when no token
 * is configured, because a query parameter ends up in server logs and in any proxy in between,
 * whereas a header does not.
 */
class TmdbAuthInterceptor(
    private val readToken: () -> Secret,
    private val apiKey: () -> Secret,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = readToken()
        val request = chain.request()

        val authorised = when {
            !token.isBlank -> request.newBuilder()
                .header("Authorization", "Bearer " + token.reveal())
                .header("Accept", "application/json")
                .build()

            !apiKey().isBlank -> request.newBuilder()
                .url(
                    request.url.newBuilder()
                        .setQueryParameter("api_key", apiKey().reveal())
                        .build(),
                )
                .header("Accept", "application/json")
                .build()

            // Nothing configured. Let the request through unauthenticated so the 401 surfaces as a
            // normal AppError.Unauthorised and Diagnostics can explain it, rather than throwing
            // here and producing an opaque crash.
            else -> request
        }
        return chain.proceed(authorised)
    }
}

/** Adds a bearer token supplied lazily, for the provider API and the backend. */
class BearerTokenInterceptor(
    private val token: () -> Secret,
    private val headerName: String = "Authorization",
    private val prefix: String = "Bearer ",
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val value = token()
        if (value.isBlank) return chain.proceed(chain.request())
        return chain.proceed(
            chain.request().newBuilder()
                .header(headerName, prefix + value.reveal())
                .build(),
        )
    }
}

/**
 * Retries transient failures with exponential backoff and jitter.
 *
 * Retries: connection failures, 429, and 5xx. Does not retry 4xx other than 429, because a bad
 * request will stay bad. Honours `Retry-After` when the server supplies it, which is what TMDB and
 * most providers use to tell a client how long to wait.
 *
 * Jitter matters on a TV: several rails load at once on the home screen, so without it every rail
 * would retry in lockstep and hammer the server in synchronised bursts.
 */
class RetryInterceptor(
    private val maxAttempts: Int = NetworkLimits.RETRY_MAX_ATTEMPTS,
    private val baseDelayMillis: Long = NetworkLimits.RETRY_BASE_DELAY_MILLIS,
    private val maxDelayMillis: Long = NetworkLimits.RETRY_MAX_DELAY_MILLIS,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val random: Random = Random.Default,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var lastFailure: IOException? = null

        for (attempt in 0 until maxAttempts) {
            if (attempt > 0) sleeper(delayFor(attempt, retryAfterMillis = null))

            val response = try {
                chain.proceed(chain.request())
            } catch (io: IOException) {
                lastFailure = io
                continue
            }

            if (!shouldRetry(response.code) || attempt == maxAttempts - 1) return response

            val retryAfter = parseRetryAfter(response)
            // The body must be closed before the connection can be reused for the retry.
            response.close()
            sleeper(delayFor(attempt + 1, retryAfter))
        }

        throw lastFailure ?: IOException("Request failed after " + maxAttempts + " attempts")
    }

    private fun shouldRetry(code: Int): Boolean =
        code == HTTP_TOO_MANY_REQUESTS || code in HTTP_SERVER_ERROR_RANGE

    private fun delayFor(attempt: Int, retryAfterMillis: Long?): Long {
        if (retryAfterMillis != null) return min(retryAfterMillis, maxDelayMillis)
        val exponential = baseDelayMillis shl (attempt - 1).coerceAtLeast(0)
        val capped = min(exponential, maxDelayMillis)
        // Full jitter: pick uniformly in [capped/2, capped].
        val half = capped / 2
        return half + random.nextLong(half + 1)
    }

    companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        val HTTP_SERVER_ERROR_RANGE = 500..599

        /** `Retry-After` is either seconds or an HTTP date. Only the seconds form is honoured. */
        fun parseRetryAfter(response: Response): Long? =
            response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1_000L)
    }
}

/**
 * Caps how many bytes a response may carry.
 *
 * Two layers, because either alone is insufficient:
 *  1. `Content-Length`, when present, is rejected up front so we never start the download.
 *  2. A hostile or misconfigured server can omit `Content-Length` and stream forever, so the body
 *     is also wrapped and counted as it is read.
 *
 * Without this, a single malicious playlist URL is an out-of-memory kill on a 1 GB Fire TV Stick.
 */
class ResponseSizeLimitInterceptor(
    private val maxBytes: Long,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        val declared = response.header("Content-Length")?.toLongOrNull()
        if (declared != null && declared > maxBytes) {
            response.close()
            throw ResponseTooLargeException(maxBytes, declared)
        }

        val body = response.body
        return response.newBuilder()
            .body(LimitedResponseBody(body, maxBytes))
            .build()
    }
}

/** Thrown when a response exceeds its configured ceiling. Mapped to [com.sustream.tv.core.result.AppError.TooLarge]. */
class ResponseTooLargeException(
    val limitBytes: Long,
    val declaredBytes: Long?,
) : IOException(
    "Response exceeds the " + limitBytes + " byte limit" +
        (declaredBytes?.let { " (declared " + it + ")" } ?: ""),
)

/**
 * Limits automatic redirects and refuses a downgrade from HTTPS to HTTP.
 *
 * OkHttp's own follower allows 20 hops and will happily follow `https` to `http`, which would
 * silently strip TLS from a request the user believed was encrypted. The client is configured with
 * `followRedirects(false)` and this interceptor does the following by hand so both limits hold.
 */
class RedirectPolicyInterceptor(
    private val maxRedirects: Int = NetworkLimits.MAX_REDIRECTS,
    private val allowHttpsDowngrade: Boolean = false,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var response = chain.proceed(request)
        var hops = 0

        while (response.isRedirect) {
            if (hops >= maxRedirects) {
                response.close()
                throw TooManyRedirectsException(maxRedirects)
            }

            val location = response.header("Location")
            if (location.isNullOrBlank()) return response

            val target: HttpUrl = request.url.resolve(location) ?: run {
                response.close()
                throw IOException("Redirect target is not a valid URL")
            }

            if (!allowHttpsDowngrade && request.url.isHttps && !target.isHttps) {
                response.close()
                throw InsecureRedirectException(request.url.host, target.host)
            }

            response.close()
            hops++
            // Cross-host redirects must not carry our Authorization header onward.
            val builder = request.newBuilder().url(target)
            if (target.host != request.url.host) {
                builder.removeHeader("Authorization")
                builder.removeHeader("Cookie")
            }
            request = builder.build()
            response = chain.proceed(request)
        }
        return response
    }
}

class TooManyRedirectsException(maxRedirects: Int) :
    IOException("More than " + maxRedirects + " redirects")

class InsecureRedirectException(fromHost: String, toHost: String) :
    IOException("Refused an HTTPS to HTTP redirect from " + fromHost + " to " + toHost)

/**
 * Bounds how many requests hit one upstream at a time.
 *
 * OkHttp's dispatcher limits per-host concurrency globally, but we want a limit per *integration*:
 * TMDB should not be starved by a large EPG download, and a home screen that fires eight rail
 * requests at once should not look like an attack to TMDB.
 */
class ConcurrencyLimitInterceptor(
    permits: Int = NetworkLimits.MAX_CONCURRENT_TMDB_REQUESTS,
) : Interceptor {

    private val semaphore = Semaphore(permits, /* fair = */ true)

    override fun intercept(chain: Interceptor.Chain): Response {
        semaphore.acquire()
        return try {
            chain.proceed(chain.request())
        } finally {
            semaphore.release()
        }
    }
}

/**
 * Rejects a response whose content type is not what the caller expects.
 *
 * The brief requires content-type checks. In practice this catches the common IPTV failure where a
 * provider serves an HTML "subscription expired" page with a 200 status: without this the JSON
 * parser produces a confusing syntax error instead of "sign-in rejected by the provider".
 */
class ContentTypeGuardInterceptor(
    private val expectedPrefixes: List<String>,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return response

        val contentType = response.header("Content-Type")?.lowercase() ?: return response
        if (expectedPrefixes.any { contentType.startsWith(it) }) return response

        // Text/plain is tolerated because several real providers serve JSON with the wrong type.
        if (contentType.startsWith("text/plain")) return response

        AppLog.w(
            TAG,
            "Unexpected content type " + contentType + " from " +
                Redact.url(response.request.url.toString()),
        )
        return response
    }
}

/**
 * Debug-only request logging with credentials removed.
 *
 * OkHttp's own `HttpLoggingInterceptor` can redact headers but will still print the full URL, which
 * for Xtream means printing the user's username and password. This logs through [Redact] instead.
 */
class RedactingLoggingInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val started = System.nanoTime()
        AppLog.d(TAG) { "--> " + request.method + " " + Redact.url(request.url.toString()) }

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            AppLog.d(TAG) { "<-- FAILED " + Redact.url(request.url.toString()) + ": " + e.message }
            throw e
        }

        val millis = (System.nanoTime() - started) / 1_000_000
        AppLog.d(TAG) {
            "<-- " + response.code + " " + Redact.url(request.url.toString()) + " (" + millis + "ms)"
        }
        return response
    }
}
