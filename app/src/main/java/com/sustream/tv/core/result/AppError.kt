package com.sustream.tv.core.result

/**
 * The complete set of ways an operation in this app can fail.
 *
 * Closed on purpose. Every `when` over an [AppError] is exhaustive at compile time, so adding a
 * failure mode forces every UI that renders errors to decide what to show. That is what stops the
 * "unknown error" catch-all from spreading, which matters here because the brief requires explicit
 * states for network loss, slow networks, expired links, quota errors and unsupported formats.
 */
sealed interface AppError {

    /** Human-facing detail that is safe to display. Never contains a credential or a full URL. */
    val detail: String?

    /** No usable connectivity, DNS failure, or the socket died. */
    data class Network(override val detail: String? = null) : AppError

    /** The request exceeded its deadline. Distinct from [Network] so the UI can offer "keep waiting". */
    data class Timeout(override val detail: String? = null) : AppError

    /** 401/403, an expired session, or a provider rejecting stored credentials. */
    data class Unauthorised(
        override val detail: String? = null,
        /** True when the caller should trigger a token refresh rather than a sign-out. */
        val refreshable: Boolean = false,
    ) : AppError

    /** 429. [retryAfterMillis] is taken from `Retry-After` when the server supplies it. */
    data class RateLimited(
        val retryAfterMillis: Long?,
        override val detail: String? = null,
    ) : AppError

    /** 404, or a title/channel that no longer exists upstream. */
    data class NotFound(override val detail: String? = null) : AppError

    /** The response arrived but could not be deserialised, or a playlist/EPG was malformed. */
    data class ParseFailed(
        override val detail: String? = null,
        /** 1-based line number for text formats such as M3U, when known. */
        val lineNumber: Int? = null,
    ) : AppError

    /** A URL used a scheme or host the app refuses to open. See `core.net.UrlValidator`. */
    data class SchemeRejected(
        val scheme: String?,
        override val detail: String? = null,
    ) : AppError

    /** A download or parse hit its configured size ceiling. */
    data class TooLarge(
        val limitBytes: Long,
        override val detail: String? = null,
    ) : AppError

    /** A resolved stream link has passed its expiry and must be re-resolved. */
    data class Expired(override val detail: String? = null) : AppError

    /** The provider account is out of quota or over a fair-use limit. */
    data class QuotaExceeded(override val detail: String? = null) : AppError

    /** The container, codec or DRM scheme cannot be played on this device. */
    data class UnsupportedFormat(override val detail: String? = null) : AppError

    /** Server-side failure (5xx) that is worth retrying. */
    data class ServerError(val statusCode: Int, override val detail: String? = null) : AppError

    /** Local storage failed: disk full, database corruption, keystore unavailable. */
    data class Storage(override val detail: String? = null) : AppError

    /** Nothing above fits. Logged with the cause; shown to the user as a generic message. */
    data class Unknown(override val detail: String? = null) : AppError
}

/** Whether retrying the identical request could plausibly succeed. Drives "Try again" buttons. */
val AppError.isRetryable: Boolean
    get() = when (this) {
        is AppError.Network,
        is AppError.Timeout,
        is AppError.RateLimited,
        is AppError.ServerError,
        is AppError.Expired,
        is AppError.Unknown,
        -> true

        is AppError.Unauthorised -> refreshable
        is AppError.NotFound,
        is AppError.ParseFailed,
        is AppError.SchemeRejected,
        is AppError.TooLarge,
        is AppError.QuotaExceeded,
        is AppError.UnsupportedFormat,
        is AppError.Storage,
        -> false
    }
