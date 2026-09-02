package com.sustream.tv.domain.model

import java.time.Instant

/**
 * Account and session models.
 *
 * Guest mode is a first-class state, not "signed out". The workbook asks for browsing without an
 * account (row 3) alongside a watchlist and history (rows 31-34), so a guest must get the full
 * local feature set. Modelling guest as its own case stops the UI from having to guess whether a
 * null user means "not loaded yet" or "deliberately anonymous".
 */

data class UserProfile(
    val id: String,
    val displayName: String,
    val email: String?,
    /** Emoji or a remote URL. The prototype uses an emoji avatar; both are supported. */
    val avatar: String?,
    val createdAt: Instant?,
    val preferredSubtitleLanguage: String?,
    val interests: List<String> = emptyList(),
)

/**
 * Tokens for an authenticated session.
 *
 * The access token is held in memory only and never persisted: a short-lived credential written to
 * disk is a credential on disk. The refresh token is persisted in the encrypted store, which is
 * the standard trade-off — it is the one thing that must survive a restart.
 */
data class AuthSession(
    val accessToken: com.sustream.tv.core.log.Secret,
    val accessTokenExpiresAt: Instant,
    val refreshToken: com.sustream.tv.core.log.Secret,
) {
    /**
     * Refresh slightly early so a request does not fail on a token that expires mid-flight.
     */
    fun needsRefreshAt(now: Instant): Boolean =
        !now.isBefore(accessTokenExpiresAt.minusSeconds(REFRESH_SKEW_SECONDS))

    companion object {
        const val REFRESH_SKEW_SECONDS = 60L
    }
}

sealed interface AuthState {

    /** Startup: we do not yet know whether a session exists. UI shows the splash, not a sign-in. */
    data object Unknown : AuthState

    /** Deliberately anonymous. Full local functionality, no server calls. */
    data object Guest : AuthState

    data class SignedIn(
        val profile: UserProfile,
        val session: AuthSession,
    ) : AuthState

    /** Signed out after a session failure, so the UI can explain why rather than just logging out. */
    data class SignedOut(val reason: SignOutReason) : AuthState

    val isSignedIn: Boolean get() = this is SignedIn
    val profileOrNull: UserProfile? get() = (this as? SignedIn)?.profile
}

enum class SignOutReason {
    USER_REQUESTED,
    /** Refresh failed, so the session cannot be recovered. */
    SESSION_EXPIRED,
    /** The server revoked the account or the device. */
    REVOKED,
}

/** Credentials submitted by a sign-up or sign-in form. */
data class Credentials(
    val email: String,
    val password: com.sustream.tv.core.log.Secret,
    val displayName: String? = null,
)

/**
 * Client-side validation, so the user gets an answer without a round trip and the server is not a
 * spell-checker. The server validates again — this is a convenience, not a security control.
 */
object CredentialRules {
    /** Deliberately permissive: the authoritative check is "did the verification email arrive". */
    private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]{2,}$")

    const val MIN_PASSWORD_LENGTH = 10

    fun isValidEmail(email: String): Boolean = EMAIL_REGEX.matches(email.trim())

    /**
     * Length only. Composition rules (a digit, a symbol, mixed case) push people towards
     * `Password1!` and are no longer recommended by NCSC or NIST; length is what matters.
     */
    fun isValidPassword(password: com.sustream.tv.core.log.Secret): Boolean =
        password.length >= MIN_PASSWORD_LENGTH
}
