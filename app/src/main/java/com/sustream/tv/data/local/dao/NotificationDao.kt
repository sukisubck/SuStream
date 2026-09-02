package com.sustream.tv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sustream.tv.data.local.entity.NotificationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {

    @Query("SELECT * FROM notification ORDER BY createdAt DESC LIMIT :limit")
    fun observeAll(limit: Int = DEFAULT_LIMIT): Flow<List<NotificationEntity>>

    @Query("SELECT COUNT(*) FROM notification WHERE isRead = 0")
    fun observeUnreadCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(notification: NotificationEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(notification: NotificationEntity): Long

    @Query("UPDATE notification SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE notification SET isRead = 1")
    suspend fun markAllRead()

    @Query("DELETE FROM notification WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Removes an existing alert for the same subject before a new one is written.
     *
     * Without this, a playlist that fails on every refresh accumulates one identical notification
     * per attempt and buries everything else.
     */
    @Query("DELETE FROM notification WHERE kind = :kind AND subjectId = :subjectId")
    suspend fun deleteForSubject(kind: String, subjectId: String)

    /** Keeps the table bounded: the oldest rows beyond the cap are dropped. */
    @Query(
        """
        DELETE FROM notification
        WHERE id NOT IN (
            SELECT id FROM notification ORDER BY createdAt DESC LIMIT :keep
        )
        """,
    )
    suspend fun trimTo(keep: Int = MAX_RETAINED)

    @Query("DELETE FROM notification")
    suspend fun clear()

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_RETAINED = 100
    }
}
