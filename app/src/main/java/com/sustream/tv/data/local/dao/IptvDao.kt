package com.sustream.tv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.sustream.tv.data.local.entity.ChannelEntity
import com.sustream.tv.data.local.entity.EpgProgrammeEntity
import com.sustream.tv.data.local.entity.PlaylistEntity
import kotlinx.coroutines.flow.Flow

/** Playlists, channels and guide data. */
@Dao
interface IptvDao {

    // ---- Playlists ----------------------------------------------------------

    @Query("SELECT * FROM playlist ORDER BY createdAt ASC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun playlist(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlist")
    suspend fun allPlaylists(): List<PlaylistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(playlist: PlaylistEntity)

    @Query("UPDATE playlist SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: String, name: String)

    @Query("UPDATE playlist SET epgUrl = :epgUrl WHERE id = :id")
    suspend fun updateEpgUrl(id: String, epgUrl: String?)

    @Query(
        """
        UPDATE playlist
        SET status = :status,
            lastErrorDetail = :detail,
            lastSyncedAt = :syncedAt,
            channelCount = :channelCount
        WHERE id = :id
        """,
    )
    suspend fun updatePlaylistStatus(
        id: String,
        status: String,
        detail: String?,
        syncedAt: Long?,
        channelCount: Int,
    )

    @Query("UPDATE playlist SET originLabel = :username WHERE id = :id")
    suspend fun updateXtreamUsername(id: String, username: String)

    /** Channels and channel favourites go with it, via the foreign key and an explicit sweep. */
    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    // ---- Channels -----------------------------------------------------------

    @Query(
        """
        SELECT * FROM channel
        WHERE (:playlistId IS NULL OR playlistId = :playlistId)
        ORDER BY playlistId ASC, ordinal ASC
        """,
    )
    fun observeChannels(playlistId: String?): Flow<List<ChannelEntity>>

    @Query(
        """
        SELECT * FROM channel
        WHERE (:playlistId IS NULL OR playlistId = :playlistId)
          AND (
            (:group IS NULL AND groupTitle IS NULL)
            OR groupTitle = :group
          )
        ORDER BY ordinal ASC
        """,
    )
    fun observeChannelsInGroup(playlistId: String?, group: String?): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channel WHERE id = :id")
    suspend fun channel(id: String): ChannelEntity?

    /**
     * One-shot read of a playlist's channels, ordered as the playlist supplied them.
     *
     * A suspend query rather than taking the first emission from [observeChannels]: the guide grid
     * wants a snapshot, and collecting a Flow only to cancel it immediately is both wasteful and
     * easy to get subtly wrong.
     */
    @Query(
        """
        SELECT * FROM channel
        WHERE playlistId = :playlistId
        ORDER BY ordinal ASC
        LIMIT :limit
        """,
    )
    suspend fun channelsFor(playlistId: String, limit: Int): List<ChannelEntity>

    /**
     * Channel search.
     *
     * `LIKE` with a leading wildcard cannot use an index, but a channel list is at most a few
     * thousand rows on a device and an FTS table for that would be more moving parts than the
     * problem deserves. Revisit if playlists routinely exceed ~20k channels.
     */
    @Query(
        """
        SELECT * FROM channel
        WHERE name LIKE '%' || :query || '%'
           OR groupTitle LIKE '%' || :query || '%'
        ORDER BY
            CASE WHEN name LIKE :query || '%' THEN 0 ELSE 1 END,
            name ASC
        LIMIT :limit
        """,
    )
    suspend fun searchChannels(query: String, limit: Int): List<ChannelEntity>

    /**
     * Distinct groups with counts, for the category filter.
     *
     * Groups come from the playlist's own `group-title` values; the app never invents categories.
     */
    @Query(
        """
        SELECT groupTitle AS name, COUNT(*) AS channelCount FROM channel
        WHERE (:playlistId IS NULL OR playlistId = :playlistId)
        GROUP BY groupTitle
        ORDER BY
            CASE WHEN groupTitle IS NULL THEN 1 ELSE 0 END,
            groupTitle ASC
        """,
    )
    fun observeCategories(playlistId: String?): Flow<List<CategoryCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannels(channels: List<ChannelEntity>)

    @Query("DELETE FROM channel WHERE playlistId = :playlistId")
    suspend fun deleteChannelsFor(playlistId: String)

    /**
     * Replaces a playlist's channels wholesale.
     *
     * Delete-then-insert in one transaction, so a refresh that removes channels actually removes
     * them and the list is never briefly empty on screen.
     */
    @Transaction
    suspend fun replaceChannels(playlistId: String, channels: List<ChannelEntity>) {
        deleteChannelsFor(playlistId)
        upsertChannels(channels)
    }

    @Query("SELECT COUNT(*) FROM channel WHERE playlistId = :playlistId")
    suspend fun channelCount(playlistId: String): Int

    @Query("SELECT DISTINCT tvgId FROM channel WHERE tvgId IS NOT NULL AND playlistId = :playlistId")
    suspend fun tvgIdsFor(playlistId: String): List<String>

    // ---- EPG ----------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgrammes(programmes: List<EpgProgrammeEntity>)

    @Query("DELETE FROM epg_programme WHERE playlistId = :playlistId")
    suspend fun deleteProgrammesFor(playlistId: String)

    @Transaction
    suspend fun replaceProgrammes(playlistId: String, programmes: List<EpgProgrammeEntity>) {
        deleteProgrammesFor(playlistId)
        upsertProgrammes(programmes)
    }

    /** Now-and-next for a set of channels: everything that has not finished yet. */
    @Query(
        """
        SELECT * FROM epg_programme
        WHERE channelTvgId IN (:tvgIds)
          AND endMillis > :now
        ORDER BY channelTvgId ASC, startMillis ASC
        """,
    )
    fun observeUpcoming(tvgIds: List<String>, now: Long): Flow<List<EpgProgrammeEntity>>

    /**
     * Guide grid, bounded to a window.
     *
     * `startMillis < to AND endMillis > from` catches programmes that straddle the window edges,
     * which a naive `BETWEEN` on `startMillis` would drop — leaving a gap at the left of the grid
     * for whatever was already running.
     */
    @Query(
        """
        SELECT * FROM epg_programme
        WHERE playlistId = :playlistId
          AND startMillis < :to
          AND endMillis > :from
        ORDER BY channelTvgId ASC, startMillis ASC
        """,
    )
    suspend fun programmesInWindow(playlistId: String, from: Long, to: Long): List<EpgProgrammeEntity>

    @Query("DELETE FROM epg_programme WHERE endMillis < :before")
    suspend fun pruneProgrammesBefore(before: Long): Int

    @Query("SELECT COUNT(*) FROM epg_programme WHERE playlistId = :playlistId")
    suspend fun programmeCount(playlistId: String): Int

    @Query("DELETE FROM epg_programme")
    suspend fun clearProgrammes()

    @Query("DELETE FROM playlist")
    suspend fun clearPlaylists()
}

/** Projection for the category filter. */
data class CategoryCount(
    val name: String?,
    val channelCount: Int,
)
