package com.sustream.tv.data.backend

import com.sustream.tv.core.log.Secret
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.domain.model.Credentials
import com.sustream.tv.domain.model.UserProfile
import java.time.Instant

/**
 * The backend, as the app needs it.
 *
 * Defined as an interface with a mock implementation because **there is no backend yet**. The
 * contract it mirrors is specified in `backend-contract/openapi.yaml`; swapping the mock for the
 * real implementation is one line in `AppContainer`.
 *
 * Keeping this narrow — auth and profile only, with library sync in its own
 * [LibrarySyncGateway] — means a test can fake exactly the surface it needs.
 */
interface BackendAuthGateway {

    /** False when no real backend is configured, so the app can present guest mode honestly. */
    val isAvailable: Boolean

    suspend fun signUp(credentials: Credentials): AppResult<AuthPayload>

    suspend fun signIn(credentials: Credentials): AppResult<AuthPayload>

    /**
     * Exchanges a refresh token for a new access token.
     *
     * The contract specifies refresh-token rotation, so the response carries a *new* refresh token
     * and the old one is invalidated server-side. Callers must persist the new one.
     */
    suspend fun refresh(refreshToken: Secret): AppResult<AuthPayload>

    suspend fun signOut(refreshToken: Secret): AppResult<Unit>

    suspend fun profile(): AppResult<UserProfile>

    suspend fun updateProfile(profile: UserProfile): AppResult<UserProfile>

    /** Server-side account deletion. Required for data-protection compliance. */
    suspend fun deleteAccount(): AppResult<Unit>
}

/** Tokens plus the profile they belong to. */
data class AuthPayload(
    val profile: UserProfile,
    val accessToken: Secret,
    val accessTokenExpiresAt: Instant,
    val refreshToken: Secret,
)
