package com.sustream.tv.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.sustream.tv.data.local.entity.FavouriteEntity
import com.sustream.tv.data.local.entity.MediaSnapshotEntity
import com.sustream.tv.data.local.entity.WatchProgressEntity
import com.sustream.tv.data.local.entity.WatchlistEntity
import kotlinx.coroutines.flow.Flow

/**
 * Watchlist, progress, history and favourites.
 *
 * The joined result types use `@Relation` rather than a hand-written `JOIN` with `@Embedded`.
 * That is deliberate: both tables have a `mediaId` column, so `SELECT w.*, m.*` produces two
 * columns of the same name and Room cannot tell them apart. `@Relation` sidesteps the collision,
 * keeps the SQL readable, and Room still resolves the relation in one extra batched query rather
 * than one per row.
 */
@Dao
interface LibraryDao {

    // ---- Media snapshots ----------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshot(snapshot: MediaSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshots(snapshots: List<MediaSnapshotEntity>)

    @Query("SELECT * FROM media_snapshot WHERE mediaId = :mediaId")
    suspend fun snapshot(mediaId: String): MediaSnapshotEntity?

    /**
     * Removes cached metadata that no user-owned row still references.
     *
     * Run after a watchlist or history deletion, so the snapshot table does not grow without bound
     * as the user browses.
     */
    @Query(
        """
        DELETE FROM media_snapshot
        WHERE mediaId NOT IN (SELECT mediaId FROM watchlist)
          AND mediaId NOT IN (SELECT mediaId FROM watch_progress)
          AND mediaId NOT IN (SELECT mediaId FROM favourite WHERE mediaId IS NOT NULL)
        """,
    )
    suspend fun pruneOrphanSnapshots(): Int

    // ---- Watchlist ----------------------------------------------------------

    @Transaction
    @Query(
        """
        SELECT * FROM watchlist
        WHERE syncState != 'PENDING_DELETE'
        ORDER BY addedAt DESC
        """,
    )
    fun observeWatchlist(): Flow<List<WatchlistWithMedia>>

    @Query("SELECT mediaId FROM watchlist WHERE syncState != 'PENDING_DELETE'")
    fun observeWatchlistIds(): Flow<List<String>>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM watchlist WHERE mediaId = :mediaId AND syncState != 'PENDING_DELETE'
        )
        """,
    )
    fun observeInWatchlist(mediaId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatchlist(entry: WatchlistEntity)

    /**
     * Adds a title and its metadata together.
     *
     * One transaction, because a watchlist row without its snapshot would render as a blank card.
     */
    @Transaction
    suspend fun addToWatchlist(snapshot: MediaSnapshotEntity, entry: WatchlistEntity) {
        upsertSnapshot(snapshot)
        upsertWatchlist(entry)
    }

    @Query("DELETE FROM watchlist WHERE mediaId = :mediaId")
    suspend fun deleteFromWatchlist(mediaId: String)

    /**
     * Tombstones instead of deleting.
     *
     * Used when signed in: the server has to be told, and a row that vanished locally before the
     * push would silently reappear on the next pull.
     */
    @Query("UPDATE watchlist SET syncState = 'PENDING_DELETE' WHERE mediaId = :mediaId")
    suspend fun markWatchlistDeleted(mediaId: String)

    @Query("SELECT * FROM watchlist WHERE syncState != 'SYNCED'")
    suspend fun pendingWatchlist(): List<WatchlistEntity>

    @Query("UPDATE watchlist SET syncState = 'SYNCED' WHERE mediaId IN (:mediaIds)")
    suspend fun markWatchlistSynced(mediaIds: List<String>)

    @Query("DELETE FROM watchlist WHERE syncState = 'PENDING_DELETE'")
    suspend fun purgeDeletedWatchlist()

    @Query("DELETE FROM watchlist")
    suspend fun clearWatchlist()

    // ---- Progress and history ----------------------------------------------

    @Transaction
    @Query("SELECT * FROM watch_progress ORDER BY updatedAt DESC LIMIT :limit")
    fun observeHistory(limit: Int): Flow<List<ProgressWithMedia>>

    /**
     * Continue Watching.
     *
     * Filtered in SQL rather than in Kotlin so the query returns twenty rows instead of the entire
     * history for the client to sift. `completed = 0` plus the fraction bounds is
     * `PlaybackProgress.isResumable` expressed in SQL.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM watch_progress
        WHERE completed = 0
          AND durationMillis > 0
          AND (CAST(positionMillis AS REAL) / durationMillis) > :startThreshold
          AND (CAST(positionMillis AS REAL) / durationMillis) < :completionThreshold
        ORDER BY updatedAt DESC
        LIMIT :limit
        """,
    )
    fun observeContinueWatching(
        startThreshold: Float,
        completionThreshold: Float,
        limit: Int,
    ): Flow<List<ProgressWithMedia>>

    @Query("SELECT * FROM watch_progress WHERE `key` = :key")
    suspend fun progress(key: String): WatchProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: WatchProgressEntity)

    @Transaction
    suspend fun saveProgress(snapshot: MediaSnapshotEntity, progress: WatchProgressEntity) {
        upsertSnapshot(snapshot)
        upsertProgress(progress)
    }

    @Query(
        """
        UPDATE watch_progress
        SET completed = :completed, syncState = 'PENDING_UPLOAD'
        WHERE `key` = :key
        """,
    )
    suspend fun setCompleted(key: String, completed: Boolean)

    @Query("DELETE FROM watch_progress WHERE `key` = :key")
    suspend fun deleteProgress(key: String)

    @Query("SELECT * FROM watch_progress WHERE syncState != 'SYNCED'")
    suspend fun pendingProgress(): List<WatchProgressEntity>

    @Query("UPDATE watch_progress SET syncState = 'SYNCED' WHERE `key` IN (:keys)")
    suspend fun markProgressSynced(keys: List<String>)

    @Query("DELETE FROM watch_progress")
    suspend fun clearProgress()

    // ---- Favourites ---------------------------------------------------------

    @Query("SELECT * FROM favourite ORDER BY addedAt DESC")
    fun observeFavourites(): Flow<List<FavouriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favourite WHERE storageKey = :storageKey)")
    fun observeIsFavourite(storageKey: String): Flow<Boolean>

    @Query("SELECT channelId FROM favourite WHERE kind = 'CHANNEL' AND channelId IS NOT NULL")
    fun observeFavouriteChannelIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavourite(favourite: FavouriteEntity)

    @Query("DELETE FROM favourite WHERE storageKey = :storageKey")
    suspend fun deleteFavourite(storageKey: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favourite WHERE storageKey = :storageKey)")
    suspend fun isFavourite(storageKey: String): Boolean

    /** Called when a playlist is deleted, so its channel favourites go with it. */
    @Query("DELETE FROM favourite WHERE kind = 'CHANNEL' AND playlistId = :playlistId")
    suspend fun deleteChannelFavourites(playlistId: String)

    @Query("DELETE FROM favourite")
    suspend fun clearFavourites()
}

/**
 * A watchlist row with its cached metadata.
 *
 * [media] is nullable because Room resolves a `@Relation` independently of the parent row. The
 * foreign key makes an orphan effectively impossible, but a nullable type means a corrupted
 * database degrades to a missing card rather than a crash.
 */
data class WatchlistWithMedia(
    @Embedded val entry: WatchlistEntity,
    @Relation(parentColumn = "mediaId", entityColumn = "mediaId")
    val media: MediaSnapshotEntity?,
)

data class ProgressWithMedia(
    @Embedded val progress: WatchProgressEntity,
    @Relation(parentColumn = "mediaId", entityColumn = "mediaId")
    val media: MediaSnapshotEntity?,
)
