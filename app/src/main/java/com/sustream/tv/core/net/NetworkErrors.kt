package com.sustream.tv.core.net

import com.sustream.tv.core.log.AppLog
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

private const val TAG = "Net"

/**
 * Turns exceptions and HTTP status codes into the closed [AppError] taxonomy.
 *
 * Every repository funnels through [safeApiCall], which means no `Throwable` escapes the data
 * layer and every UI error state is reachable and testable.
 */
object NetworkErrors {

    fun fromThrowable(throwable: Throwable): AppError = when (throwable) {
        is ResponseTooLargeException ->
            AppError.TooLarge(throwable.limitBytes, "The response was too large to read.")

        is TooManyRedirectsException ->
            AppError.SchemeRejected(null, "That address redirected too many times.")

        is InsecureRedirectException ->
            AppError.SchemeRejected("http", "That address tried to drop out of HTTPS.")

        is SocketTimeoutException -> AppError.Timeout(throwable.message)

        is UnknownHostException ->
            AppError.Network("That server could not be found.")

        is SSLException ->
            AppError.Network("The secure connection to that server failed.")

        is SerializationException ->
            AppError.ParseFailed("The response was not in the expected format.")

        is IOException -> AppError.Network(throwable.message)

        else -> {
            AppLog.e(TAG, "Unmapped failure: " + throwable.javaClass.simpleName, throwable)
            AppError.Unknown(throwable.message)
        }
    }

    /**
     * Maps a status code. [retryAfterMillis] comes from the `Retry-After` header when present.
     *
     * 401 is reported as refreshable so an [okhttp3.Authenticator] or the calling repository can
     * attempt a token refresh before giving up and signing the user out. 403 is not refreshable:
     * the credentials were understood and denied.
     */
    fun fromStatus(
        code: Int,
        retryAfterMillis: Long? = null,
        detail: String? = null,
    ): AppError = when (code) {
        UNAUTHORISED -> AppError.Unauthorised(detail, refreshable = true)
        FORBIDDEN -> AppError.Unauthorised(detail, refreshable = false)
        NOT_FOUND, GONE -> AppError.NotFound(detail)
        PAYLOAD_TOO_LARGE -> AppError.TooLarge(0, detail)
        TOO_MANY_REQUESTS -> AppError.RateLimited(retryAfterMillis, detail)
        UNSUPPORTED_MEDIA_TYPE -> AppError.UnsupportedFormat(detail)
        PAYMENT_REQUIRED -> AppError.QuotaExceeded(detail)
        in SERVER_ERRORS -> AppError.ServerError(code, detail)
        in CLIENT_ERRORS -> AppError.Unknown(detail ?: ("Request rejected (" + code + ")"))
        else -> AppError.Unknown(detail ?: ("Unexpected status " + code))
    }

    private const val UNAUTHORISED = 401
    private const val PAYMENT_REQUIRED = 402
    private const val FORBIDDEN = 403
    private const val NOT_FOUND = 404
    private const val GONE = 410
    private const val PAYLOAD_TOO_LARGE = 413
    private const val UNSUPPORTED_MEDIA_TYPE = 415
    private const val TOO_MANY_REQUESTS = 429
    private val CLIENT_ERRORS = 400..499
    private val SERVER_ERRORS = 500..599
}

/**
 * Runs a suspending network call and converts any failure into an [AppResult].
 *
 * [CancellationException] is deliberately rethrown: swallowing it would break structured
 * concurrency, so a cancelled coroutine would keep running and its result would be reported to a
 * view model that no longer exists.
 */
suspend inline fun <T> safeApiCall(
    crossinline block: suspend () -> T,
): AppResult<T> = try {
    AppResult.Success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (throwable: Throwable) {
    AppResult.Failure(NetworkErrors.fromThrowable(throwable))
}

/**
 * Same as [safeApiCall] but for Retrofit calls that return a [retrofit2.Response], so a non-2xx
 * status becomes a typed error rather than an exception with a status buried in the message.
 */
suspend inline fun <T> safeApiResponse(
    crossinline block: suspend () -> retrofit2.Response<T>,
): AppResult<T> = try {
    val response = block()
    val body = response.body()
    when {
        response.isSuccessful && body != null -> AppResult.Success(body)

        response.isSuccessful ->
            AppResult.Failure(AppError.ParseFailed("The server returned an empty response."))

        else -> AppResult.Failure(
            NetworkErrors.fromStatus(
                code = response.code(),
                retryAfterMillis = response.headers()["Retry-After"]
                    ?.toLongOrNull()
                    ?.times(1_000L),
            ),
        )
    }
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (throwable: Throwable) {
    AppResult.Failure(NetworkErrors.fromThrowable(throwable))
}
