package com.sustream.tv.domain.model

import com.sustream.tv.core.result.AppError
import java.time.Instant

/**
 * External provider integration models.
 *
 * Scope is deliberately narrow: connect, report status, and list what is *already in the user's own
 * account*. There is no search, no discovery and no lookup by hash or magnet. See
 * docs/IMPLEMENTATION_PLAN.md section 1.2.
 */

enum class ProviderId {
    TORBOX,
    ;

    val displayName: String get() = "TorBox"
}

sealed interface ProviderConnection {

    data object NotConnected : ProviderConnection

    /** A connect attempt is in flight. */
    data object Connecting : ProviderConnection

    data class Connected(
        val account: ProviderAccount,
        val checkedAt: Instant,
    ) : ProviderConnection

    /**
     * Credentials are stored but the last call failed. Distinct from [NotConnected] so the UI can
     * say "connection problem" and offer a retry rather than asking for the key again.
     */
    data class Problem(
        val error: AppError,
        val checkedAt: Instant,
    ) : ProviderConnection

    val isConnected: Boolean get() = this is Connected
}

/**
 * Account summary for the Settings screen.
 *
 * Carries no token and no full email. Everything here is either non-identifying or already
 * masked, so it is safe to show on a shared television screen — which matters, because a TV is a
 * shared display in a living room, not a personal phone.
 */
data class ProviderAccount(
    val provider: ProviderId,
    /** Masked, e.g. `"a***@example.com"`. Built by the data layer; never the raw address. */
    val maskedIdentifier: String,
    val plan: String?,
    val expiresAt: Instant?,
    /** Null when the provider does not report a quota, rather than a guessed "unlimited". */
    val quotaUsedBytes: Long?,
    val quotaTotalBytes: Long?,
) {
    val hasQuotaInfo: Boolean get() = quotaUsedBytes != null && quotaTotalBytes != null

    val quotaFraction: Float?
        get() {
            val used = quotaUsedBytes ?: return null
            val total = quotaTotalBytes ?: return null
            if (total <= 0L) return null
            return (used.toFloat() / total).coerceIn(0f, 1f)
        }
}

/**
 * An item the user already holds in their provider cloud account.
 *
 * This is what makes the integration lawful: the app reads a library the user built, it does not
 * go looking for content. The user's own naming is preserved as-is rather than parsed for quality
 * claims.
 */
data class ProviderLibraryItem(
    val id: String,
    val name: String,
    val sizeBytes: Long?,
    val addedAt: Instant?,
    /** Files inside the item, since a single entry often contains a whole season. */
    val files: List<ProviderFile>,
    val isReady: Boolean,
)

data class ProviderFile(
    val id: String,
    val name: String,
    val sizeBytes: Long?,
    /** Best-effort container guess from the extension, for filtering out non-media files. */
    val container: StreamContainer,
) {
    val isPlayableMedia: Boolean
        get() = PLAYABLE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    private companion object {
        val PLAYABLE_EXTENSIONS = listOf(
            ".mp4", ".mkv", ".m4v", ".mov", ".ts", ".m2ts", ".avi", ".webm", ".m3u8", ".mpd",
        )
    }
}

/**
 * Health of one integration, for Settings -> Diagnostics.
 *
 * Only things the client can actually measure. The prototype's admin panel invented figures such
 * as `scrapeSuccessRate: '98.6%'` and `debridUptime: '99.94%'`; nothing here is fabricated.
 */
data class IntegrationHealth(
    val name: String,
    val state: HealthState,
    val detail: String?,
    val lastCheckedAt: Instant?,
    /** Round-trip time of the last successful call, when one has been made. */
    val lastLatencyMillis: Long?,
)

enum class HealthState {
    OK,
    DEGRADED,
    FAILING,
    /** Nothing configured, so nothing to report. Not a failure. */
    NOT_CONFIGURED,
    UNKNOWN,
}
