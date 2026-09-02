package com.sustream.tv.domain.repository

import com.sustream.tv.core.result.AppResult
import com.sustream.tv.domain.model.AppSettings
import com.sustream.tv.domain.model.AppNotification
import com.sustream.tv.domain.model.AuthSession
import com.sustream.tv.domain.model.AuthState
import com.sustream.tv.domain.model.ContinueWatchingItem
import com.sustream.tv.domain.model.Credentials
import com.sustream.tv.domain.model.Favourite
import com.sustream.tv.domain.model.HistoryEntry
import com.sustream.tv.domain.model.IntegrationHealth
import com.sustream.tv.domain.model.MediaId
import com.sustream.tv.domain.model.MediaItem
import com.sustream.tv.domain.model.PlaybackPreferences
import com.sustream.tv.domain.model.PlaybackProgress
import com.sustream.tv.domain.model.ResolvedStream
import com.sustream.tv.domain.model.SignOutReason
import com.sustream.tv.domain.model.SubtitlePreferences
import com.sustream.tv.domain.model.UserProfile
import com.sustream.tv.domain.model.WatchlistEntry
import kotlinx.coroutines.flow.Flow

/**
 * Saved titles. Local-first: works fully for a guest, syncs when signed in.
 */
interface WatchlistRepository {

    fun observeWatchlist(): Flow<List<WatchlistEntry>>

    /** Ids only. Cheap enough to observe from every card so the bookmark state is always right. */
    fun observeWatchlistIds(): Flow<Set<MediaId>>

    fun observeContains(id: MediaId): Flow<Boolean>

    /** Takes the whole [MediaItem] so the entry renders offline without a TMDB round trip. */
    suspend fun add(item: MediaItem): AppResult<Unit>

    suspend fun remove(id: MediaId): AppResult<Unit>

    suspend fun toggle(item: MediaItem): AppResult<Boolean>

    /** Pushes pending local changes and pulls remote ones. No-op for a guest. */
    suspend fun sync(): AppResult<Unit>

    suspend fun clear(): AppResult<Unit>
}

/** Viewing history, resume positions and the Continue Watching rail. */
interface HistoryRepository {

    fun observeHistory(limit: Int = HISTORY_LIMIT): Flow<List<HistoryEntry>>

    fun observeContinueWatching(limit: Int = CONTINUE_LIMIT): Flow<List<ContinueWatchingItem>>

    suspend fun progressFor(
        id: MediaId,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ): AppResult<PlaybackProgress?>

    /**
     * Records where the user got to.
     *
     * Called frequently during playback, so implementations must debounce writes rather than
     * committing on every position callback — the brief asks for progress to be persisted safely
     * while avoiding excessive writes, and a Fire TV Stick's flash storage is not fast.
     */
    suspend fun saveProgress(
        item: MediaItem,
        progress: PlaybackProgress,
    ): AppResult<Unit>

    /** Flushes any debounced write. Called when playback stops or the app backgrounds. */
    suspend fun flushPendingProgress(): AppResult<Unit>

    suspend fun markWatched(item: MediaItem, seasonNumber: Int?, episodeNumber: Int?): AppResult<Unit>

    suspend fun markUnwatched(id: MediaId, seasonNumber: Int?, episodeNumber: Int?): AppResult<Unit>

    suspend fun removeFromHistory(id: MediaId, seasonNumber: Int?, episodeNumber: Int?): AppResult<Unit>

    suspend fun sync(): AppResult<Unit>

    suspend fun clear(): AppResult<Unit>

    companion object {
        const val HISTORY_LIMIT = 200
        const val CONTINUE_LIMIT = 20
    }
}

/** Favourite titles and favourite live channels, in one place. */
interface FavouritesRepository {
    fun observeFavourites(): Flow<List<Favourite>>
    fun observeIsFavourite(favourite: Favourite): Flow<Boolean>
    suspend fun toggle(favourite: Favourite): AppResult<Boolean>
    suspend fun clear(): AppResult<Unit>
}

/**
 * Accounts and sessions.
 *
 * Guest is a real state, not an absence of one: see [AuthState].
 */
interface AuthRepository {

    fun observeAuthState(): Flow<AuthState>

    /** Restores a persisted session at startup, refreshing the access token if needed. */
    suspend fun restoreSession(): AppResult<AuthState>

    suspend fun signUp(credentials: Credentials): AppResult<AuthState.SignedIn>

    suspend fun signIn(credentials: Credentials): AppResult<AuthState.SignedIn>

    /**
     * Enters guest mode. No server call, no tokens.
     *
     * Recorded rather than merely implied so the app can tell a deliberate guest from a user who
     * has not finished onboarding, and therefore knows whether to show the sign-in screen again.
     */
    suspend fun continueAsGuest(): AppResult<AuthState.Guest>

    suspend fun signOut(reason: SignOutReason = SignOutReason.USER_REQUESTED): AppResult<Unit>

    /**
     * Exchanges the refresh token.
     *
     * Implementations must de-duplicate concurrent calls: a home screen firing eight requests will
     * produce eight simultaneous 401s, and eight refresh attempts against a rotating refresh token
     * would invalidate each other and sign the user out.
     */
    suspend fun refreshSession(): AppResult<AuthSession>

    suspend fun updateProfile(profile: UserProfile): AppResult<UserProfile>

    /** Server-side deletion, then a full local wipe. Required for data-protection compliance. */
    suspend fun deleteAccount(): AppResult<Unit>

    /**
     * Merges a guest's local watchlist and history into the account being signed into.
     *
     * Called once, immediately after a first successful sign-in, so a guest who has built up a
     * watchlist does not lose it by creating an account.
     */
    suspend fun mergeGuestDataIntoAccount(): AppResult<Unit>
}

/** Device-local preferences. */
interface SettingsRepository {
    fun observeSettings(): Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun updatePlayback(transform: (PlaybackPreferences) -> PlaybackPreferences): AppResult<Unit>
    suspend fun updateSubtitles(transform: (SubtitlePreferences) -> SubtitlePreferences): AppResult<Unit>
    suspend fun updateIptv(transform: (com.sustream.tv.domain.model.IptvPreferences) -> com.sustream.tv.domain.model.IptvPreferences): AppResult<Unit>
    suspend fun updateStremioAddon(transform: (com.sustream.tv.domain.model.StremioAddonPreferences) -> com.sustream.tv.domain.model.StremioAddonPreferences): AppResult<Unit>
    suspend fun updateNotifications(transform: (com.sustream.tv.domain.model.NotificationPreferences) -> com.sustream.tv.domain.model.NotificationPreferences): AppResult<Unit>
    suspend fun setInterests(interests: List<String>): AppResult<Unit>
    suspend fun setOnboardingComplete(complete: Boolean): AppResult<Unit>
    /** Restores defaults. Does not touch the watchlist, history or playlists. */
    suspend fun resetToDefaults(): AppResult<Unit>
}

/**
 * Notifications.
 *
 * Abstracted over the source so a push transport can be added later without touching the UI. The
 * shipped implementation derives alerts from integration health and watchlist availability.
 */
interface NotificationRepository {
    fun observeNotifications(): Flow<List<AppNotification>>
    fun observeUnreadCount(): Flow<Int>
    suspend fun markRead(id: String): AppResult<Unit>
    suspend fun markAllRead(): AppResult<Unit>
    suspend fun dismiss(id: String): AppResult<Unit>
    /** Re-derives alerts from current integration state. Called when Live TV or Settings opens. */
    suspend fun refresh(): AppResult<Unit>
    suspend fun clear(): AppResult<Unit>
}

/**
 * Local diagnostics.
 *
 * This is the client's share of the workbook's admin requirements: health of *this device's*
 * integrations only, never global state, and never a privileged operation. See
 * docs/ADMIN_BOUNDARY.md.
 */
interface DiagnosticsRepository {
    fun observeHealth(): Flow<List<IntegrationHealth>>
    suspend fun runChecks(): AppResult<List<IntegrationHealth>>
    /** Bytes currently held by the HTTP and image caches. */
    suspend fun cacheSizeBytes(): Long
    suspend fun clearCaches(): AppResult<Unit>
    /** Wipes playlists, credentials, watchlist, history and settings from this device. */
    suspend fun resetAllLocalData(): AppResult<Unit>
    /** Redacted, human-readable report the user can review before choosing to share it. */
    suspend fun buildReport(): String
}

/**
 * Playback session state and stream resolution, sitting between the player UI and the source
 * adapters.
 */
interface PlaybackRepository {

    /**
     * Chooses a source and validates it.
     *
     * Kept here rather than in [AuthorisedSourceRepository] so the *policy* — which of several
     * authorised sources to prefer, whether to honour the quality setting, how many times to fall
     * back — lives in one place and is testable without any network.
     */
    suspend fun resolveForPlayback(
        source: com.sustream.tv.domain.model.PlayableSource,
        startPositionMillis: Long,
    ): AppResult<ResolvedStream>

    /** Re-resolves an expired link, keeping the position. */
    suspend fun reResolve(
        stream: ResolvedStream,
        atPositionMillis: Long,
    ): AppResult<ResolvedStream>

    /**
     * The next episode after the one given, for autoplay.
     *
     * Returns null at the end of a season with no following season, which is the signal for the
     * player to stop rather than loop.
     */
    suspend fun nextEpisode(
        showId: MediaId,
        seasonNumber: Int,
        episodeNumber: Int,
    ): AppResult<com.sustream.tv.domain.model.EpisodeRef?>
}
