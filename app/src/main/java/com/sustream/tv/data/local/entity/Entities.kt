package com.sustream.tv.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * On-device schema.
 *
 * Design notes that shaped it:
 *
 *  * **[MediaSnapshotEntity] is a shared cache of card-level metadata**, referenced by the
 *    watchlist, progress and favourites tables. Without it, the same title's name and artwork path
 *    would be stored three times and could drift out of step. With it, Library and Continue Watching
 *    render fully offline from one join rather than from a TMDB round trip.
 *  * **One progress table, not two.** Resume position and viewing history are the same fact viewed
 *    two ways: history is every row ordered by `updatedAt`, Continue Watching is the subset whose
 *    completion fraction falls between the thresholds. The workbook lists them as separate features
 *    (rows 33 and 34) but they are one implementation.
 *  * **`syncState` on user-owned rows** so a guest's data can be merged upward on first sign-in
 *    rather than discarded.
 *  * **Credentials are absent.** Xtream passwords live in `EncryptedSharedPreferences`, keyed by
 *    playlist id. Nothing in this database is a secret, which is why it needs no encryption and can
 *    be safely included in a diagnostic dump.
 */

@Entity(
    tableName = "media_snapshot",
    indices = [Index("type")],
)
data class MediaSnapshotEntity(
    /** `movie:603` form. See `domain.model.MediaId`. */
    @PrimaryKey val mediaId: String,
    val type: String,
    val title: String,
    val posterPath: String?,
    val backdropPath: String?,
    val releaseYear: Int?,
    val voteAverage: Double?,
    val voteCount: Int,
    val primaryGenre: String?,
    val overview: String,
    /** When this snapshot was last refreshed from the catalogue. */
    val updatedAt: Long,
)

@Entity(
    tableName = "watchlist",
    indices = [Index("addedAt"), Index("syncState")],
    foreignKeys = [
        ForeignKey(
            entity = MediaSnapshotEntity::class,
            parentColumns = ["mediaId"],
            childColumns = ["mediaId"],
            // A snapshot is never deleted while it is referenced, so CASCADE here is a safety net
            // rather than an expected path.
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class WatchlistEntity(
    @PrimaryKey val mediaId: String,
    val addedAt: Long,
    val syncState: String,
)

/**
 * Where the user got to in a film or an episode.
 *
 * [key] is `movie:603` for a film and `tv:1396#2x4` for an episode, so one table covers both and a
 * show can hold independent progress per episode.
 */
@Entity(
    tableName = "watch_progress",
    indices = [
        Index("mediaId"),
        Index("updatedAt"),
        Index("syncState"),
        Index("completed"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = MediaSnapshotEntity::class,
            parentColumns = ["mediaId"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class WatchProgressEntity(
    @PrimaryKey val key: String,
    val mediaId: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val positionMillis: Long,
    val durationMillis: Long,
    val updatedAt: Long,
    /**
     * Set once the completion threshold is crossed, and kept even if the user later scrubs back.
     * Stored rather than derived because the threshold is a user setting: deriving it would silently
     * change what counts as "watched" in the history whenever the setting changed.
     */
    val completed: Boolean,
    val syncState: String,
)

/**
 * Favourites, covering both titles and live channels.
 *
 * One table with a discriminator rather than two, so the Favourites screen is a single query. A
 * channel favourite carries its playlist id so the row can be removed when the playlist is deleted.
 */
@Entity(
    tableName = "favourite",
    indices = [Index("kind"), Index("playlistId"), Index("addedAt")],
)
data class FavouriteEntity(
    /** `title:movie:603` or `channel:<playlistId>:<channelId>`. */
    @PrimaryKey val storageKey: String,
    val kind: String,
    val mediaId: String?,
    val channelId: String?,
    val playlistId: String?,
    val addedAt: Long,
)

@Entity(
    tableName = "playlist",
    indices = [Index("createdAt")],
)
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** `M3U_URL`, `M3U_FILE` or `XTREAM`. */
    val originType: String,
    /** The URL, the document URI, or the Xtream server URL, depending on [originType]. */
    val originValue: String,
    /** Display name of a picked file, or the Xtream username. Never a password. */
    val originLabel: String?,
    val epgUrl: String?,
    val channelCount: Int,
    val lastSyncedAt: Long?,
    val status: String,
    val cleartextAcknowledged: Boolean,
    val lastErrorDetail: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "channel",
    indices = [
        Index("playlistId"),
        Index("groupTitle"),
        Index("tvgId"),
        Index("name"),
        Index(value = ["playlistId", "ordinal"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            // Deleting a playlist must take its channels with it, or the channel list would show
            // entries whose stream credentials no longer exist.
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChannelEntity(
    @PrimaryKey val id: String,
    val playlistId: String,
    val number: String?,
    val name: String,
    val logoUrl: String?,
    val groupTitle: String?,
    val tvgId: String?,
    val streamUrl: String,
    val ordinal: Int,
)

/**
 * A programme from an EPG feed.
 *
 * Keyed by a synthetic id built from channel plus start time, so re-importing the same feed updates
 * rows instead of duplicating them. Indexed on the time window because every query is "what is on
 * between X and Y".
 */
@Entity(
    tableName = "epg_programme",
    indices = [
        Index("channelTvgId"),
        Index("startMillis"),
        Index("endMillis"),
        Index(value = ["channelTvgId", "startMillis"]),
        Index("playlistId"),
    ],
)
data class EpgProgrammeEntity(
    @PrimaryKey val id: String,
    val playlistId: String,
    val channelTvgId: String,
    val title: String,
    val description: String?,
    val startMillis: Long,
    val endMillis: Long,
    val category: String?,
    val episodeLabel: String?,
)

@Entity(
    tableName = "notification",
    indices = [Index("createdAt"), Index("isRead")],
)
data class NotificationEntity(
    @PrimaryKey val id: String,
    /** `SERVICE_ALERT` or `CONTENT_AVAILABLE`. */
    val kind: String,
    val createdAt: Long,
    val isRead: Boolean,
    val title: String,
    val body: String,
    /** Set for content alerts. */
    val mediaId: String?,
    /** Playlist id or provider name, for service alerts. */
    val subjectId: String?,
    val subjectName: String?,
)
