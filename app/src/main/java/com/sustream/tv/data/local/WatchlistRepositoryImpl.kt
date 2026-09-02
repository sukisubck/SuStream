package com.sustream.tv.data.local

import com.sustream.tv.core.log.AppLog
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.core.util.DispatcherProvider
import com.sustream.tv.core.util.TimeSource
import com.sustream.tv.data.backend.LibrarySyncGateway
import com.sustream.tv.data.local.dao.LibraryDao
import com.sustream.tv.data.local.entity.WatchlistEntity
import com.sustream.tv.domain.model.MediaId
import com.sustream.tv.domain.model.MediaItem
import com.sustream.tv.domain.model.SyncState
import com.sustream.tv.domain.model.WatchlistEntry
import com.sustream.tv.domain.repository.WatchlistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private const val TAG = "Watchlist"

/**
 * Local-first watchlist.
 *
 * Writes land in Room immediately and only then queue for sync, so pressing the bookmark button
 * updates the UI on the next frame whether or not there is a network and whether or not the user
 * has an account. That ordering is the whole point: a TV remote press that appears to do nothing
 * for two seconds reads as a broken app.
 *
 * The full [MediaItem] is stored alongside the entry (as a `media_snapshot` row) rather than just
 * the id, so the Library screen renders offline with no TMDB round trip.
 */
class WatchlistRepositoryImpl(
    private val dao: LibraryDao,
    private val syncGateway: LibrarySyncGateway,
    private val dispatchers: DispatcherProvider,
    private val timeSource: TimeSource,
) : WatchlistRepository {

    override fun observeWatchlist(): Flow<List<WatchlistEntry>> =
        dao.observeWatchlist().map { rows -> rows.mapNotNull { it.toDomain() } }

    override fun observeWatchlistIds(): Flow<Set<MediaId>> =
        dao.observeWatchlistIds().map { ids ->
            ids.mapNotNull { MediaId.parseOrNull(it) }.toSet()
        }

    override fun observeContains(id: MediaId): Flow<Boolean> =
        dao.observeInWatchlist(id.value)

    override suspend fun add(item: MediaItem): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            val now = timeSource.nowMillis()
            dao.addToWatchlist(
                snapshot = item.toSnapshot(now),
                entry = WatchlistEntity(
                    mediaId = item.id.value,
                    addedAt = now,
                    // Marked pending even for a guest. If they sign in later, the merge picks the
                    // row up; if they never do, the state is simply never read.
                    syncState = SyncState.PENDING_UPLOAD.key,
                ),
            )
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppLog.e(TAG, "Could not add " + item.id, throwable)
            AppResult.Failure(AppError.Storage("That could not be saved to your watchlist."))
        }
    }

    override suspend fun remove(id: MediaId): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            if (syncGateway.isActive) {
                // Tombstone, so the removal survives long enough to be pushed. A hard delete would
                // let the next pull resurrect the row.
                dao.markWatchlistDeleted(id.value)
            } else {
                dao.deleteFromWatchlist(id.value)
                dao.pruneOrphanSnapshots()
            }
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppLog.e(TAG, "Could not remove " + id, throwable)
            AppResult.Failure(AppError.Storage("That could not be removed from your watchlist."))
        }
    }

    /** @return true when the item is now in the watchlist. */
    override suspend fun toggle(item: MediaItem): AppResult<Boolean> =
        withContext(dispatchers.io) {
            val present = dao.observeInWatchlist(item.id.value).first()
            val result = if (present) remove(item.id) else add(item)
            when (result) {
                is AppResult.Success -> AppResult.Success(!present)
                is AppResult.Failure -> result
            }
        }

    /**
     * Reconciles local and server state.
     *
     * Order matters. Pushing before pulling means a title the user added while offline is not
     * wiped by a pull that has not seen it yet.
     */
    override suspend fun sync(): AppResult<Unit> = withContext(dispatchers.io) {
        if (!syncGateway.isActive) return@withContext AppResult.Success(Unit)

        try {
            val pending = dao.pendingWatchlist()
            val additions = pending
                .filter { it.syncState == SyncState.PENDING_UPLOAD.key }
                .mapNotNull { MediaId.parseOrNull(it.mediaId) }
            val removals = pending
                .filter { it.syncState == SyncState.PENDING_DELETE.key }
                .mapNotNull { MediaId.parseOrNull(it.mediaId) }

            if (additions.isNotEmpty()) {
                when (val push = syncGateway.pushWatchlistAdditions(additions)) {
                    is AppResult.Success ->
                        dao.markWatchlistSynced(additions.map { it.value })

                    is AppResult.Failure -> return@withContext push
                }
            }

            if (removals.isNotEmpty()) {
                when (val push = syncGateway.pushWatchlistRemovals(removals)) {
                    is AppResult.Success -> {
                        dao.purgeDeletedWatchlist()
                        dao.pruneOrphanSnapshots()
                    }

                    is AppResult.Failure -> return@withContext push
                }
            }

            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppLog.e(TAG, "Watchlist sync failed", throwable)
            AppResult.Failure(AppError.Unknown("The watchlist could not be synchronised."))
        }
    }

    override suspend fun clear(): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            dao.clearWatchlist()
            dao.pruneOrphanSnapshots()
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError.Storage("The watchlist could not be cleared."))
        }
    }
}
