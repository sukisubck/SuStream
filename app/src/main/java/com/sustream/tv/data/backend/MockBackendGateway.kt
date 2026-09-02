package com.sustream.tv.data.backend

import com.sustream.tv.core.log.Secret
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.core.util.TimeSource
import com.sustream.tv.domain.model.Credentials
import com.sustream.tv.domain.model.CredentialRules
import com.sustream.tv.domain.model.MediaId
import com.sustream.tv.domain.model.PlaybackProgress
import com.sustream.tv.domain.model.UserProfile
import java.util.UUID

/**
 * In-memory backend, used until a real one exists.
 *
 * Why this is worth having rather than simply disabling sign-in:
 *
 *  * The whole authenticated path — sign up, sign in, token refresh, profile edit, sign out, guest
 *    merge, account deletion — is exercisable and testable today, so wiring the real backend later
 *    is a substitution rather than a first integration.
 *  * It enforces the *same* validation rules the contract specifies, so the client's error handling
 *    is developed against realistic failures (duplicate email, wrong password, expired refresh
 *    token) instead of only against success.
 *
 * What it deliberately does **not** do: persist anything across process death. State living only in
 * memory makes it obvious this is not a real backend, and prevents anyone mistaking it for one.
 *
 * See `backend-contract/openapi.yaml` for the contract this mirrors, and
 * `backend-contract/mock-server/` for a standalone HTTP version of the same thing.
 */
class MockBackendGateway(
    private val timeSource: TimeSource,
) : BackendAuthGateway, LibrarySyncGateway {

    /** Email to (password, profile). Passwords are held as [Secret], as they would be anywhere. */
    private val accounts = mutableMapOf<String, MockAccount>()

    /** Valid refresh tokens to the email they belong to. Rotation removes the old entry. */
    private val refreshTokens = mutableMapOf<String, String>()

    private var signedInEmail: String? = null

    private val remoteWatchlist = mutableSetOf<MediaId>()
    private val remoteProgress = mutableMapOf<String, PlaybackProgress>()

    override val isAvailable: Boolean = true

    /** Active only once someone has signed in, so a guest performs no sync work at all. */
    override val isActive: Boolean get() = signedInEmail != null

    // ---- Auth ---------------------------------------------------------------

    override suspend fun signUp(credentials: Credentials): AppResult<AuthPayload> {
        val email = credentials.email.trim().lowercase()

        if (!CredentialRules.isValidEmail(email)) {
            return AppResult.Failure(AppError.Unknown("That email address is not valid."))
        }
        if (!CredentialRules.isValidPassword(credentials.password)) {
            return AppResult.Failure(
                AppError.Unknown(
                    "Use at least " + CredentialRules.MIN_PASSWORD_LENGTH + " characters.",
                ),
            )
        }
        if (accounts.containsKey(email)) {
            return AppResult.Failure(
                AppError.Unknown("An account already exists for that email address."),
            )
        }

        val profile = UserProfile(
            id = UUID.randomUUID().toString(),
            displayName = credentials.displayName?.trim()?.takeIf { it.isNotEmpty() }
                ?: email.substringBefore('@'),
            email = email,
            avatar = null,
            createdAt = timeSource.now(),
            preferredSubtitleLanguage = null,
        )
        accounts[email] = MockAccount(password = credentials.password, profile = profile)
        return issueTokens(email)
    }

    override suspend fun signIn(credentials: Credentials): AppResult<AuthPayload> {
        val email = credentials.email.trim().lowercase()
        val account = accounts[email]
            ?: return AppResult.Failure(
                // Same message for an unknown email and a wrong password: distinguishing them tells
                // an attacker which addresses are registered.
                AppError.Unauthorised("Those details were not recognised.", refreshable = false),
            )

        if (account.password.reveal() != credentials.password.reveal()) {
            return AppResult.Failure(
                AppError.Unauthorised("Those details were not recognised.", refreshable = false),
            )
        }
        return issueTokens(email)
    }

    override suspend fun refresh(refreshToken: Secret): AppResult<AuthPayload> {
        val email = refreshTokens[refreshToken.reveal()]
            ?: return AppResult.Failure(
                AppError.Unauthorised("Your session has expired.", refreshable = false),
            )
        // Rotation: the presented token is consumed, exactly as the contract specifies. Reusing a
        // rotated token is how token-theft detection works, so the mock enforces it too.
        refreshTokens.remove(refreshToken.reveal())
        return issueTokens(email)
    }

    override suspend fun signOut(refreshToken: Secret): AppResult<Unit> {
        refreshTokens.remove(refreshToken.reveal())
        signedInEmail = null
        return AppResult.Success(Unit)
    }

    override suspend fun profile(): AppResult<UserProfile> {
        val email = signedInEmail
            ?: return AppResult.Failure(
                AppError.Unauthorised("Not signed in.", refreshable = false),
            )
        val profile = accounts[email]?.profile
            ?: return AppResult.Failure(AppError.NotFound("That account no longer exists."))
        return AppResult.Success(profile)
    }

    override suspend fun updateProfile(profile: UserProfile): AppResult<UserProfile> {
        val email = signedInEmail
            ?: return AppResult.Failure(
                AppError.Unauthorised("Not signed in.", refreshable = false),
            )
        val account = accounts[email]
            ?: return AppResult.Failure(AppError.NotFound("That account no longer exists."))
        accounts[email] = account.copy(profile = profile)
        return AppResult.Success(profile)
    }

    override suspend fun deleteAccount(): AppResult<Unit> {
        val email = signedInEmail
            ?: return AppResult.Failure(
                AppError.Unauthorised("Not signed in.", refreshable = false),
            )
        accounts.remove(email)
        refreshTokens.entries.removeAll { it.value == email }
        remoteWatchlist.clear()
        remoteProgress.clear()
        signedInEmail = null
        return AppResult.Success(Unit)
    }

    private fun issueTokens(email: String): AppResult<AuthPayload> {
        val account = accounts[email]
            ?: return AppResult.Failure(AppError.NotFound("That account no longer exists."))

        val refresh = UUID.randomUUID().toString()
        refreshTokens[refresh] = email
        signedInEmail = email

        return AppResult.Success(
            AuthPayload(
                profile = account.profile,
                accessToken = Secret("mock-access-" + UUID.randomUUID()),
                accessTokenExpiresAt = timeSource.now().plusSeconds(ACCESS_TOKEN_TTL_SECONDS),
                refreshToken = Secret(refresh),
            ),
        )
    }

    // ---- Library sync -------------------------------------------------------

    override suspend fun pushWatchlistAdditions(ids: List<MediaId>): AppResult<Unit> {
        if (!isActive) return notSignedIn()
        remoteWatchlist += ids
        return AppResult.Success(Unit)
    }

    override suspend fun pushWatchlistRemovals(ids: List<MediaId>): AppResult<Unit> {
        if (!isActive) return notSignedIn()
        remoteWatchlist -= ids.toSet()
        return AppResult.Success(Unit)
    }

    override suspend fun fetchWatchlist(): AppResult<List<MediaId>> {
        if (!isActive) return notSignedIn()
        return AppResult.Success(remoteWatchlist.toList())
    }

    override suspend fun pushProgress(progress: List<PlaybackProgress>): AppResult<Unit> {
        if (!isActive) return notSignedIn()
        progress.forEach { entry ->
            // Last-write-wins on `updatedAt`, matching the documented sync policy.
            val existing = remoteProgress[entry.key]
            if (existing == null || entry.updatedAt.isAfter(existing.updatedAt)) {
                remoteProgress[entry.key] = entry
            }
        }
        return AppResult.Success(Unit)
    }

    override suspend fun fetchProgress(): AppResult<List<PlaybackProgress>> {
        if (!isActive) return notSignedIn()
        return AppResult.Success(remoteProgress.values.toList())
    }

    private fun <T> notSignedIn(): AppResult<T> =
        AppResult.Failure(AppError.Unauthorised("Not signed in.", refreshable = false))

    private data class MockAccount(
        val password: Secret,
        val profile: UserProfile,
    )

    private companion object {
        /** 15 minutes, matching the contract, so the refresh path is exercised in development. */
        const val ACCESS_TOKEN_TTL_SECONDS = 900L
    }
}

/**
 * Used when the app is deliberately offline-only: no backend configured and guest mode assumed.
 *
 * Distinct from [MockBackendGateway], which simulates a working backend. This one reports itself
 * unavailable so the UI can say "sign-in is not configured" rather than offering a form that will
 * always fail.
 */
class UnavailableBackendGateway : BackendAuthGateway {
    override val isAvailable: Boolean = false

    override suspend fun signUp(credentials: Credentials) = unavailable<AuthPayload>()
    override suspend fun signIn(credentials: Credentials) = unavailable<AuthPayload>()
    override suspend fun refresh(refreshToken: Secret) = unavailable<AuthPayload>()
    override suspend fun signOut(refreshToken: Secret) = AppResult.Success(Unit)
    override suspend fun profile() = unavailable<UserProfile>()
    override suspend fun updateProfile(profile: UserProfile) = unavailable<UserProfile>()
    override suspend fun deleteAccount() = unavailable<Unit>()

    private fun <T> unavailable(): AppResult<T> = AppResult.Failure(
        AppError.Unknown(
            "Accounts are not available in this build. Everything is kept on this device.",
        ),
    )
}
