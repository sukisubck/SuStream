package com.sustream.tv.data.local

import com.sustream.tv.data.local.dao.CategoryCount
import com.sustream.tv.data.local.dao.ProgressWithMedia
import com.sustream.tv.data.local.dao.WatchlistWithMedia
import com.sustream.tv.data.local.entity.ChannelEntity
import com.sustream.tv.data.local.entity.EpgProgrammeEntity
import com.sustream.tv.data.local.entity.FavouriteEntity
import com.sustream.tv.data.local.entity.MediaSnapshotEntity
import com.sustream.tv.data.local.entity.NotificationEntity
import com.sustream.tv.data.local.entity.PlaylistEntity
import com.sustream.tv.data.local.entity.WatchProgressEntity
import com.sustream.tv.domain.model.AppNotification
import com.sustream.tv.domain.model.Channel
import com.sustream.tv.domain.model.ChannelCategory
import com.sustream.tv.domain.model.ContinueWatchingItem
import com.sustream.tv.domain.model.EpgProgramme
import com.sustream.tv.domain.model.Favourite
import com.sustream.tv.domain.model.HistoryEntry
import com.sustream.tv.domain.model.MediaId
import com.sustream.tv.domain.model.MediaItem
import com.sustream.tv.domain.model.MediaType
import com.sustream.tv.domain.model.NotificationChannelId
import com.sustream.tv.domain.model.PlaybackProgress
import com.sustream.tv.domain.model.Playlist
import com.sustream.tv.domain.model.PlaylistOrigin
import com.sustream.tv.domain.model.PlaylistStatus
import com.sustream.tv.domain.model.ProviderId
import com.sustream.tv.domain.model.ServiceSubject
import com.sustream.tv.domain.model.SyncState
import com.sustream.tv.domain.model.WatchlistEntry
import java.time.Instant

/**
 * Room entities to domain models and back.
 *
 * Kept as top-level functions in one file so the whole persistence boundary is readable at once,
 * and so the mapping is unit-testable without a database.
 *
 * Everything returns null rather than throwing on a row that cannot be represented — a `mediaId`
 * that no longer parses, an origin type from a future schema version. A single unreadable row
 * should cost that row, not the whole screen.
 */

// ---- Media --------------------------------------------------------------------

fun MediaItem.toSnapshot(now: Long): MediaSnapshotEntity = MediaSnapshotEntity(
    mediaId = id.value,
    type = type.key,
    title = title,
    posterPath = posterPath,
    backdropPath = backdropPath,
    releaseYear = releaseYear,
    voteAverage = voteAverage,
    voteCount = voteCount,
    primaryGenre = primaryGenre,
    overview = overview,
    updatedAt = now,
)

fun MediaSnapshotEntity.toDomain(): MediaItem? {
    val id = MediaId.parseOrNull(mediaId) ?: return null
    val mediaType = MediaType.fromKey(type) ?: return null
    return MediaItem(
        id = id,
        type = mediaType,
        title = title,
        posterPath = posterPath,
        backdropPath = backdropPath,
        releaseYear = releaseYear,
        voteAverage = voteAverage,
        voteCount = voteCount,
        primaryGenre = primaryGenre,
        overview = overview,
    )
}

// ---- Watchlist ----------------------------------------------------------------

fun WatchlistWithMedia.toDomain(): WatchlistEntry? {
    val item = media?.toDomain() ?: return null
    return WatchlistEntry(item = item, addedAt = Instant.ofEpochMilli(entry.addedAt))
}

// ---- Progress -----------------------------------------------------------------

fun WatchProgressEntity.toDomain(): PlaybackProgress? {
    val id = MediaId.parseOrNull(mediaId) ?: return null
    return PlaybackProgress(
        mediaId = id,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        positionMillis = positionMillis,
        durationMillis = durationMillis,
        updatedAt = Instant.ofEpochMilli(updatedAt),
    )
}

fun PlaybackProgress.toEntity(
    completed: Boolean,
    syncState: SyncState,
): WatchProgressEntity = WatchProgressEntity(
    key = key,
    mediaId = mediaId.value,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    positionMillis = positionMillis,
    durationMillis = durationMillis,
    updatedAt = updatedAt.toEpochMilli(),
    completed = completed,
    syncState = syncState.key,
)

fun ProgressWithMedia.toHistoryEntry(): HistoryEntry? {
    val item = media?.toDomain() ?: return null
    return HistoryEntry(
        item = item,
        seasonNumber = progress.seasonNumber,
        episodeNumber = progress.episodeNumber,
        watchedAt = Instant.ofEpochMilli(progress.updatedAt),
        positionMillis = progress.positionMillis,
        durationMillis = progress.durationMillis,
        completed = progress.completed,
    )
}

fun ProgressWithMedia.toContinueWatching(): ContinueWatchingItem? {
    val item = media?.toDomain() ?: return null
    val domainProgress = progress.toDomain() ?: return null
    val label = if (progress.seasonNumber != null && progress.episodeNumber != null) {
        // Padded so `S02E04` sorts and aligns consistently in a rail.
        "S" + progress.seasonNumber.toString().padStart(2, '0') +
            "E" + progress.episodeNumber.toString().padStart(2, '0')
    } else {
        null
    }
    return ContinueWatchingItem(item = item, progress = domainProgress, episodeLabel = label)
}

// ---- Favourites ---------------------------------------------------------------

fun Favourite.toEntity(now: Long): FavouriteEntity = when (this) {
    is Favourite.Title -> FavouriteEntity(
        storageKey = storageKey,
        kind = FAVOURITE_KIND_TITLE,
        mediaId = id.value,
        channelId = null,
        playlistId = null,
        addedAt = now,
    )

    is Favourite.LiveChannel -> FavouriteEntity(
        storageKey = storageKey,
        kind = FAVOURITE_KIND_CHANNEL,
        mediaId = null,
        channelId = channelId,
        playlistId = playlistId,
        addedAt = now,
    )
}

fun FavouriteEntity.toDomain(): Favourite? = when (kind) {
    FAVOURITE_KIND_TITLE -> MediaId.parseOrNull(mediaId)?.let { Favourite.Title(it) }
    FAVOURITE_KIND_CHANNEL -> {
        val channel = channelId
        val playlist = playlistId
        if (channel != null && playlist != null) {
            Favourite.LiveChannel(channelId = channel, playlistId = playlist)
        } else {
            null
        }
    }

    else -> null
}

const val FAVOURITE_KIND_TITLE = "TITLE"
const val FAVOURITE_KIND_CHANNEL = "CHANNEL"

// ---- Playlists ----------------------------------------------------------------

fun PlaylistEntity.toDomain(): Playlist? {
    val origin = when (originType) {
        ORIGIN_M3U_URL -> PlaylistOrigin.M3uUrl(originValue)
        ORIGIN_M3U_FILE -> PlaylistOrigin.M3uFile(
            documentUri = originValue,
            displayName = originLabel ?: name,
        )

        ORIGIN_XTREAM -> PlaylistOrigin.Xtream(
            serverUrl = originValue,
            username = originLabel.orEmpty(),
        )

        else -> return null
    }
    return Playlist(
        id = id,
        name = name,
        origin = origin,
        channelCount = channelCount,
        lastSyncedAt = lastSyncedAt?.let { Instant.ofEpochMilli(it) },
        status = runCatching { PlaylistStatus.valueOf(status) }
            .getOrDefault(PlaylistStatus.NEVER_SYNCED),
        epgUrl = epgUrl,
        cleartextAcknowledged = cleartextAcknowledged,
        lastErrorDetail = lastErrorDetail,
        createdAt = Instant.ofEpochMilli(createdAt),
    )
}

fun Playlist.toEntity(): PlaylistEntity {
    val (type, value, label) = when (val o = origin) {
        is PlaylistOrigin.M3uUrl -> Triple(ORIGIN_M3U_URL, o.url, null)
        is PlaylistOrigin.M3uFile -> Triple(ORIGIN_M3U_FILE, o.documentUri, o.displayName)
        is PlaylistOrigin.Xtream -> Triple(ORIGIN_XTREAM, o.serverUrl, o.username)
    }
    return PlaylistEntity(
        id = id,
        name = name,
        originType = type,
        originValue = value,
        originLabel = label,
        epgUrl = epgUrl,
        channelCount = channelCount,
        lastSyncedAt = lastSyncedAt?.toEpochMilli(),
        status = status.name,
        cleartextAcknowledged = cleartextAcknowledged,
        lastErrorDetail = lastErrorDetail,
        createdAt = createdAt?.toEpochMilli() ?: 0L,
    )
}

const val ORIGIN_M3U_URL = "M3U_URL"
const val ORIGIN_M3U_FILE = "M3U_FILE"
const val ORIGIN_XTREAM = "XTREAM"

// ---- Channels -----------------------------------------------------------------

fun ChannelEntity.toDomain(isFavourite: Boolean = false): Channel = Channel(
    id = id,
    playlistId = playlistId,
    number = number,
    name = name,
    logoUrl = logoUrl,
    group = groupTitle,
    tvgId = tvgId,
    streamUrl = streamUrl,
    isFavourite = isFavourite,
    ordinal = ordinal,
)

fun Channel.toEntity(): ChannelEntity = ChannelEntity(
    id = id,
    playlistId = playlistId,
    number = number,
    name = name,
    logoUrl = logoUrl,
    groupTitle = group,
    tvgId = tvgId,
    streamUrl = streamUrl,
    ordinal = ordinal,
)

/**
 * A null group means the playlist left the channel ungrouped, which is different from a group
 * literally called "Ungrouped". The synthetic key keeps the two apart in the filter UI.
 */
fun CategoryCount.toDomain(): ChannelCategory = ChannelCategory(
    name = name ?: ChannelCategory.UNGROUPED,
    channelCount = channelCount,
)

// ---- EPG ----------------------------------------------------------------------

fun EpgProgrammeEntity.toDomain(): EpgProgramme = EpgProgramme(
    channelTvgId = channelTvgId,
    title = title,
    description = description,
    start = Instant.ofEpochMilli(startMillis),
    end = Instant.ofEpochMilli(endMillis),
    category = category,
    episodeLabel = episodeLabel,
)

fun EpgProgramme.toEntity(playlistId: String): EpgProgrammeEntity = EpgProgrammeEntity(
    // Channel plus start time is the natural key: re-importing a feed updates rows rather than
    // duplicating the same programme on every refresh.
    id = playlistId + "|" + channelTvgId + "|" + start.toEpochMilli(),
    playlistId = playlistId,
    channelTvgId = channelTvgId,
    title = title,
    description = description,
    startMillis = start.toEpochMilli(),
    endMillis = end.toEpochMilli(),
    category = category,
    episodeLabel = episodeLabel,
)

// ---- Notifications ------------------------------------------------------------

fun NotificationEntity.toDomain(): AppNotification? = when (kind) {
    NOTIFICATION_KIND_SERVICE -> {
        val subjectIdentifier = subjectId ?: return null
        val subject = if (subjectIdentifier.startsWith(SUBJECT_PROVIDER_PREFIX)) {
            val providerName = subjectIdentifier.removePrefix(SUBJECT_PROVIDER_PREFIX)
            val provider = ProviderId.entries.firstOrNull { it.name == providerName }
                ?: return null
            ServiceSubject.ProviderSubject(provider)
        } else {
            ServiceSubject.PlaylistSubject(
                playlistId = subjectIdentifier,
                playlistName = subjectName ?: subjectIdentifier,
            )
        }
        AppNotification.ServiceAlert(
            id = id,
            createdAt = Instant.ofEpochMilli(createdAt),
            isRead = isRead,
            subject = subject,
            detail = body,
        )
    }

    NOTIFICATION_KIND_CONTENT -> {
        val media = MediaId.parseOrNull(mediaId) ?: return null
        AppNotification.ContentAvailable(
            id = id,
            createdAt = Instant.ofEpochMilli(createdAt),
            isRead = isRead,
            mediaId = media,
            title = title,
            sourceName = subjectName.orEmpty(),
        )
    }

    else -> null
}

const val NOTIFICATION_KIND_SERVICE = "SERVICE_ALERT"
const val NOTIFICATION_KIND_CONTENT = "CONTENT_AVAILABLE"
const val SUBJECT_PROVIDER_PREFIX = "provider:"

/** Which Android notification channel a domain notification belongs to. */
fun AppNotification.channelId(): NotificationChannelId = when (this) {
    is AppNotification.ServiceAlert -> NotificationChannelId.SERVICE_ALERTS
    is AppNotification.ContentAvailable -> NotificationChannelId.NEW_CONTENT
}
