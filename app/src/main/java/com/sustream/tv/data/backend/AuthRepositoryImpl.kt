package com.sustream.tv.data.backend

import com.sustream.tv.core.log.AppLog
import com.sustream.tv.core.log.Secret
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.core.util.DispatcherProvider
import com.sustream.tv.core.util.TimeSource
import com.sustream.tv.data.prefs.SecureCredentialStore
import com.sustream.tv.domain.model.AuthSession
import com.sustream.tv.domain.model.AuthState
import com.sustream.tv.domain.model.Credentials
import com.sustream.tv.domain.model.SignOutReason
import com.sustream.tv.domain.model.UserProfile
import com.sustream.tv.domain.repository.AuthRepository
import com.sustream.tv.domain.repository.HistoryRepository
import com.sustream.tv.domain.repository.SettingsRepository
import com.sustream.tv.domain.repository.WatchlistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "Auth"

/**
 * Accounts and sessions.
 *
 * ## Token handling
 *
 * The access token lives in memory only — writing a fifteen-minute credential to disk widens the
 * exposure for nothing. The refresh token is persisted in [SecureCredentialStore], because it is the
 * one thing that has to survive a restart.
 *
 * ## Single-flight refresh
 *
 * [refreshSession] is guarded by a mutex and re-checks whether another caller already refreshed
 * before doing the work. This is not premature: a home screen fires eight rail requests at once, so
 * an expired token produces eight simultaneous 401s. With rotating refresh tokens, eight concurrent
 * refresh attempts would invalidate each other and sign the user out — which is exactly the bug this
 * prevents.
 *
 * ## Guest mode
 *
 * A first-class state, not an absence of one. A guest gets the full local feature set and makes no
 * server calls at all; [mergeGuestDataIntoAccount] then pushes what they built up when they sign in,
 * so creating an account never costs them their watchlist.
 */
class AuthRepositoryImpl(
    private val gateway: BackendAuthGateway,
    private val credentialStore: SecureCredentialStore,
    private val watchlistRepository: WatchlistRepository,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    private val timeSource: TimeSource,
) : AuthRepository {

    private val state = MutableStateFlow<AuthState>(AuthState.Unknown)
    private val refreshMutex = Mutex()

    override fun observeAuthState(): Flow<AuthState> = state.asStateFlow()

    override suspend fun restoreSession(): AppResult<AuthState> = withContext(dispatchers.io) {
        if (!gateway.isAvailable) {
            state.value = AuthState.Guest
            return@withContext AppResult.Success(state.value)
        }

        val stored = credentialStore.refreshToken()
        if (stored == null) {
            // No token: either a fresh install or a deliberate guest. Onboarding completion is what
            // distinguishes them, so a returning guest is not shown the sign-in screen again.
            val onboarded = settingsRepository.current().onboardingComplete
            state.value = if (onboarded) {
                AuthState.Guest
            } else {
                AuthState.SignedOut(SignOutReason.USER_REQUESTED)
            }
            return@withContext AppResult.Success(state.value)
        }

        when (val refreshed = gateway.refresh(stored)) {
            is AppResult.Success -> {
                applyPayload(refreshed.value)
                AppResult.Success(state.value)
            }

            is AppResult.Failure -> {
                AppLog.w(TAG, "Stored session could not be restored: " + refreshed.error)
                credentialStore.removeRefreshToken()
                state.value = AuthState.SignedOut(SignOutReason.SESSION_EXPIRED)
                AppResult.Success(state.value)
            }
        }
    }

    override suspend fun signUp(credentials: Credentials): AppResult<AuthState.SignedIn> =
        withContext(dispatchers.io) {
            when (val result = gateway.signUp(credentials)) {
                is AppResult.Failure -> result
                is AppResult.Success -> {
                    val signedIn = applyPayload(result.value)
                    // A brand-new account starts empty, so anything already on the device belongs
                    // to the person who just created it.
                    mergeGuestDataIntoAccount()
                    AppResult.Success(signedIn)
                }
            }
        }

    override suspend fun signIn(credentials: Credentials): AppResult<AuthState.SignedIn> =
        withContext(dispatchers.io) {
            when (val result = gateway.signIn(credentials)) {
                is AppResult.Failure -> result
                is AppResult.Success -> {
                    val signedIn = applyPayload(result.value)
                    mergeGuestDataIntoAccount()
                    AppResult.Success(signedIn)
                }
            }
        }

    override suspend fun continueAsGuest(): AppResult<AuthState.Guest> =
        withContext(dispatchers.io) {
            // Recorded so a returning guest is not asked again on every launch.
            settingsRepository.setOnboardingComplete(true)
            state.value = AuthState.Guest
            AppResult.Success(AuthState.Guest)
        }

    override suspend fun signOut(reason: SignOutReason): AppResult<Unit> =
        withContext(dispatchers.io) {
            credentialStore.refreshToken()?.let { gateway.signOut(it) }
            credentialStore.removeRefreshToken()
            state.value = AuthState.SignedOut(reason)
            // Local data is deliberately kept. Signing out is not "delete my library", and offering
            // to clear it is a separate, explicit action in Settings.
            AppResult.Success(Unit)
        }

    override suspend fun refreshSession(): AppResult<AuthSession> = refreshMutex.withLock {
        // Another caller may have refreshed while this one waited for the lock.
        val current = state.value
        if (current is AuthState.SignedIn && !current.session.needsRefreshAt(timeSource.now())) {
            return@withLock AppResult.Success(current.session)
        }

        val stored = credentialStore.refreshToken()
            ?: return@withLock AppResult.Failure(
                AppError.Unauthorised("Your session has expired.", refreshable = false),
            )

        when (val refreshed = gateway.refresh(stored)) {
            is AppResult.Success -> {
                val signedIn = applyPayload(refreshed.value)
                AppResult.Success(signedIn.session)
            }

            is AppResult.Failure -> {
                credentialStore.removeRefreshToken()
                state.value = AuthState.SignedOut(SignOutReason.SESSION_EXPIRED)
                refreshed
            }
        }
    }

    override suspend fun updateProfile(profile: UserProfile): AppResult<UserProfile> =
        withContext(dispatchers.io) {
            when (val result = gateway.updateProfile(profile)) {
                is AppResult.Failure -> result
                is AppResult.Success -> {
                    val current = state.value
                    if (current is AuthState.SignedIn) {
                        state.value = current.copy(profile = result.value)
                    }
                    result
                }
            }
        }

    override suspend fun deleteAccount(): AppResult<Unit> = withContext(dispatchers.io) {
        when (val result = gateway.deleteAccount()) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                // Server first, then a full local wipe. Doing it the other way round would leave a
                // user with no local data and an account that still exists if the call failed.
                credentialStore.clearAll()
                watchlistRepository.clear()
                historyRepository.clear()
                settingsRepository.resetToDefaults()
                state.value = AuthState.SignedOut(SignOutReason.USER_REQUESTED)
                AppResult.Success(Unit)
            }
        }
    }

    /**
     * Pushes locally-held library data up to the account that has just been signed into.
     *
     * Merge, never replace. A guest who has built a twenty-title watchlist and then signs in must
     * end up with those twenty titles plus whatever the account already had — losing either side
     * would be the user's data, silently discarded.
     */
    override suspend fun mergeGuestDataIntoAccount(): AppResult<Unit> =
        withContext(dispatchers.io) {
            val watchlist = watchlistRepository.sync()
            val history = historyRepository.sync()

            when {
                watchlist is AppResult.Failure -> watchlist
                history is AppResult.Failure -> history
                else -> AppResult.Success(Unit)
            }
        }

    private fun applyPayload(payload: AuthPayload): AuthState.SignedIn {
        credentialStore.putRefreshToken(payload.refreshToken)
        val signedIn = AuthState.SignedIn(
            profile = payload.profile,
            session = AuthSession(
                accessToken = payload.accessToken,
                accessTokenExpiresAt = payload.accessTokenExpiresAt,
                refreshToken = payload.refreshToken,
            ),
        )
        state.value = signedIn
        return signedIn
    }

    /** Current access token, for the OkHttp interceptor. Empty when not signed in. */
    fun currentAccessToken(): Secret =
        (state.value as? AuthState.SignedIn)?.session?.accessToken ?: Secret.EMPTY
}
