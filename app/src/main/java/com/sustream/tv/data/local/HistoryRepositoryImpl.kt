package com.sustream.tv.data.local

import com.sustream.tv.core.log.AppLog
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.core.util.DispatcherProvider
import com.sustream.tv.core.util.TimeSource
import com.sustream.tv.data.backend.LibrarySyncGateway
import com.sustream.tv.data.local.dao.LibraryDao
import com.sustream.tv.domain.model.ContinueWatchingItem
import com.sustream.tv.domain.model.HistoryEntry
import com.sustream.tv.domain.model.MediaId
import com.sustream.tv.domain.model.MediaItem
import com.sustream.tv.domain.model.PlaybackProgress
import com.sustream.tv.domain.model.SyncState
import com.sustream.tv.domain.repository.HistoryRepository
import com.sustream.tv.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

private const val TAG = "History"

/**
 * Viewing history, resume positions and Continue Watching.
 *
 * ## Write debouncing
 *
 * ExoPlayer's position callback fires several times a second. Persisting each one would mean tens of
 * thousands of writes over a film, on a device whose storage is slow eMMC or worse. The brief calls
 * for progress to be persisted safely while avoiding excessive writes, and the compromise here is:
 *
 *  * hold the latest position in memory;
 *  * commit at most once per [WRITE_INTERVAL_MILLIS], or immediately when the position has moved by
 *    more than [SIGNIFICANT_JUMP_MILLIS] (a seek, which the user would notice losing);
 *  * always commit on [flushPendingProgress], which the player calls when playback stops and when
 *    the app backgrounds.
 *
 * The worst case is losing a few seconds of position on a hard process kill, which is invisible to
 * the user. The alternative — writing every callback — is a measurable battery and I/O cost for no
 * benefit.
 */
class HistoryRepositoryImpl(
    private val dao: LibraryDao,
    private val settingsRepository: SettingsRepository,
    private val syncGateway: LibrarySyncGateway,
    private val dispatchers: DispatcherProvider,
    private val timeSource: TimeSource,
) : HistoryRepository {

    private val pendingLock = Mutex()
    private var pendingItem: MediaItem? = null
    private var pendingProgress: PlaybackProgress? = null
    private var lastWriteAtMillis: Long = 0L
    private var lastWrittenPositionMillis: Long = 0L

    override fun observeHistory(limit: Int): Flow<List<HistoryEntry>> =
        dao.observeHistory(limit).map { rows -> rows.mapNotNull { it.toHistoryEntry() } }

    /**
     * Continue Watching.
     *
     * Re-derived whenever the completion threshold changes, because that setting decides what counts
     * as finished, and a user who lowers it expects the rail to update immediately rather than after
     * the next playback.
     */
    override fun observeContinueWatching(limit: Int): Flow<List<ContinueWatchingItem>> =
        settingsRepository.observeSettings().flatMapLatest { settings ->
            dao.observeContinueWatching(
                startThreshold = PlaybackProgress.DEFAULT_START_THRESHOLD,
                completionThreshold = settings.playback.completionThreshold,
                limit = limit,
            ).map { rows -> rows.mapNotNull { it.toContinueWatching() } }
        }

    override suspend fun progressFor(
        id: MediaId,
        seasonNumber: Int?,
        episodeNumber: Int?,
    ): AppResult<PlaybackProgress?> = withContext(dispatchers.io) {
        try {
            val key = PlaybackProgress.keyFor(id, seasonNumber, episodeNumber)

            // An in-flight debounced write is newer than the database, so it wins. Without this,
            // leaving and re-entering the player within the debounce window would resume from the
            // stale persisted position.
            pendingLock.withLock {
                pendingProgress?.takeIf { it.key == key }
            }?.let { return@withContext AppResult.Success(it) }

            AppResult.Success(dao.progress(key)?.toDomain())
        } catch (throwable: Throwable) {
            AppLog.e(TAG, "Could not read progress for " + id, throwable)
            AppResult.Failure(AppError.Storage("Playback position could not be read."))
        }
    }

    override suspend fun saveProgress(
        item: MediaItem,
        progress: PlaybackProgress,
    ): AppResult<Unit> {
        val shouldCommit = pendingLock.withLock {
            pendingItem = item
            pendingProgress = progress

            val now = timeSource.nowMillis()
            val elapsed = now - lastWriteAtMillis
            val jumped = kotlin.math.abs(progress.positionMillis - lastWrittenPositionMillis) >
                SIGNIFICANT_JUMP_MILLIS

            elapsed >= WRITE_INTERVAL_MILLIS || jumped
        }

        return if (shouldCommit) commit() else AppResult.Success(Unit)
    }

    override suspend fun flushPendingProgress(): AppResult<Unit> = commit()

    private suspend fun commit(): AppResult<Unit> = withContext(dispatchers.io) {
        val (item, progress) = pendingLock.withLock {
            val i = pendingItem
            val p = pendingProgress
            if (i == null || p == null) return@withContext AppResult.Success(Unit)
            lastWriteAtMillis = timeSource.nowMillis()
            lastWrittenPositionMillis = p.positionMillis
            i to p
        }

        try {
            val threshold = settingsRepository.current().playback.completionThreshold
            dao.saveProgress(
                snapshot = item.toSnapshot(timeSource.nowMillis()),
                progress = progress.toEntity(
                    completed = progress.isWatched(threshold),
                    syncState = SyncState.PENDING_UPLOAD,
                ),
            )
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppLog.e(TAG, "Could not save progress", throwable)
            AppResult.Failure(AppError.Storage("Playback position could not be saved."))
        }
    }

    override suspend fun markWatched(
        item: MediaItem,
        seasonNumber: Int?,
        episodeNumber: Int?,
    ): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            val key = PlaybackProgress.keyFor(item.id, seasonNumber, episodeNumber)
            val existing = dao.progress(key)

            if (existing == null) {
                // Marking something watched that was never played still needs a row, so it appears
                // in history. A nominal duration is used because none is known; the `completed`
                // flag is what History actually reads.
                val now = timeSource.now()
                dao.saveProgress(
                    snapshot = item.toSnapshot(now.toEpochMilli()),
                    progress = PlaybackProgress(
                        mediaId = item.id,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        positionMillis = NOMINAL_DURATION_MILLIS,
                        durationMillis = NOMINAL_DURATION_MILLIS,
                        updatedAt = now,
                    ).toEntity(completed = true, syncState = SyncState.PENDING_UPLOAD),
                )
            } else {
                dao.setCompleted(key, completed = true)
            }
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError.Storage("That could not be marked as watched."))
        }
    }

    override suspend fun markUnwatched(
        id: MediaId,
        seasonNumber: Int?,
        episodeNumber: Int?,
    ): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            dao.setCompleted(
                key = PlaybackProgress.keyFor(id, seasonNumber, episodeNumber),
                completed = false,
            )
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError.Storage("That could not be marked as unwatched."))
        }
    }

    override suspend fun removeFromHistory(
        id: MediaId,
        seasonNumber: Int?,
        episodeNumber: Int?,
    ): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            dao.deleteProgress(PlaybackProgress.keyFor(id, seasonNumber, episodeNumber))
            dao.pruneOrphanSnapshots()
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError.Storage("That could not be removed from your history."))
        }
    }

    override suspend fun sync(): AppResult<Unit> = withContext(dispatchers.io) {
        if (!syncGateway.isActive) return@withContext AppResult.Success(Unit)

        try {
            // Anything held in memory must reach the database before it can be pushed.
            flushPendingProgress()

            val pending = dao.pendingProgress()
            if (pending.isEmpty()) return@withContext AppResult.Success(Unit)

            val domain = pending.mapNotNull { it.toDomain() }
            when (val push = syncGateway.pushProgress(domain)) {
                is AppResult.Success -> {
                    dao.markProgressSynced(pending.map { it.key })
                    AppResult.Success(Unit)
                }

                is AppResult.Failure -> push
            }
        } catch (throwable: Throwable) {
            AppLog.e(TAG, "History sync failed", throwable)
            AppResult.Failure(AppError.Unknown("Your history could not be synchronised."))
        }
    }

    override suspend fun clear(): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            pendingLock.withLock {
                pendingItem = null
                pendingProgress = null
            }
            dao.clearProgress()
            dao.pruneOrphanSnapshots()
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError.Storage("Your history could not be cleared."))
        }
    }

    private companion object {
        /** At most one write every ten seconds during steady playback. */
        const val WRITE_INTERVAL_MILLIS = 10_000L

        /** A jump larger than this is a seek, and is committed at once. */
        const val SIGNIFICANT_JUMP_MILLIS = 30_000L

        /**
         * Placeholder duration for a title marked watched without being played. Only the
         * `completed` flag is read for such rows, so the exact value is immaterial — it exists so
         * the fraction arithmetic has a non-zero denominator.
         */
        const val NOMINAL_DURATION_MILLIS = 1L
    }
}
