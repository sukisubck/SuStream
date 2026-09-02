package com.sustream.tv.data.backend

import com.sustream.tv.core.result.AppResult
import com.sustream.tv.domain.model.MediaId
import com.sustream.tv.domain.model.PlaybackProgress

/**
 * The narrow slice of the backend that the local library repositories need.
 *
 * Deliberately not the whole `BackendApi`: [com.sustream.tv.data.local.WatchlistRepositoryImpl] and
 * its siblings should be testable without a fake of the entire backend surface, and they have no
 * business being able to call, say, account deletion.
 *
 * [NoOpLibrarySyncGateway] is installed for a guest and whenever no real backend is configured, so
 * the sync code path is always present and always exercised, rather than being a branch that only
 * runs in production.
 */
interface LibrarySyncGateway {

    /** False for a guest, so callers can skip the work entirely rather than making no-op calls. */
    val isActive: Boolean

    suspend fun pushWatchlistAdditions(ids: List<MediaId>): AppResult<Unit>

    suspend fun pushWatchlistRemovals(ids: List<MediaId>): AppResult<Unit>

    /** Server truth for the watchlist, used to reconcile after a push. */
    suspend fun fetchWatchlist(): AppResult<List<MediaId>>

    suspend fun pushProgress(progress: List<PlaybackProgress>): AppResult<Unit>

    suspend fun fetchProgress(): AppResult<List<PlaybackProgress>>
}

/** Guest mode, and any build with no backend configured. */
object NoOpLibrarySyncGateway : LibrarySyncGateway {
    override val isActive: Boolean = false
    override suspend fun pushWatchlistAdditions(ids: List<MediaId>) = AppResult.Success(Unit)
    override suspend fun pushWatchlistRemovals(ids: List<MediaId>) = AppResult.Success(Unit)
    override suspend fun fetchWatchlist(): AppResult<List<MediaId>> = AppResult.Success(emptyList())
    override suspend fun pushProgress(progress: List<PlaybackProgress>) = AppResult.Success(Unit)
    override suspend fun fetchProgress(): AppResult<List<PlaybackProgress>> =
        AppResult.Success(emptyList())
}
