package com.sustream.tv.domain.model

import java.time.Instant

/**
 * Watchlist, history and resume models.
 *
 * All local-first. A guest gets the full feature set on-device; signing in merges what is already
 * there upward rather than discarding it. [SyncState] is what makes that merge possible.
 */

enum class SyncState {
    /** Matches the server, or the user is a guest and there is nothing to match. */
    SYNCED,

    /** Created or changed locally and not yet pushed. */
    PENDING_UPLOAD,

    /** Deleted locally; the tombstone must be pushed before the row is dropped. */
    PENDING_DELETE,
    ;

    val key: String get() = name
}

data class WatchlistEntry(
    val item: MediaItem,
    val addedAt: Instant,
)

/**
 * Where the user got to in a title.
 *
 * Films key on [mediaId] alone. Episodes additionally carry season and episode, so a show has one
 * progress row per episode and "continue watching" can point at the right one.
 */
data class PlaybackProgress(
    val mediaId: MediaId,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val positionMillis: Long,
    val durationMillis: Long,
    val updatedAt: Instant,
) {
    val fraction: Float
        get() = if (durationMillis > 0L) {
            (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
        } else {
            0f
        }

    /**
     * Whether this counts as finished.
     *
     * The threshold is not 100%: nobody sits through the credits, so a title watched to 92% is
     * finished for the purposes of Continue Watching. Configurable in Settings.
     */
    fun isWatched(completionThreshold: Float): Boolean =
        durationMillis > 0L && fraction >= completionThreshold

    /**
     * Whether this belongs on the Continue Watching rail.
     *
     * Excludes both ends: something barely started is noise, and something finished should drop
     * off the rail rather than sit there forever.
     */
    fun isResumable(
        startThreshold: Float = DEFAULT_START_THRESHOLD,
        completionThreshold: Float = DEFAULT_COMPLETION_THRESHOLD,
    ): Boolean = durationMillis > 0L &&
        fraction > startThreshold &&
        fraction < completionThreshold

    val isEpisode: Boolean get() = seasonNumber != null && episodeNumber != null

    /** Composite key used for the Room primary key and for map lookups. */
    val key: String
        get() = if (isEpisode) {
            mediaId.value + "#" + seasonNumber + "x" + episodeNumber
        } else {
            mediaId.value
        }

    companion object {
        /** Under 2% is an accidental open, not a viewing. */
        const val DEFAULT_START_THRESHOLD = 0.02f

        /** Over 92% is finished. Matches the setting's default. */
        const val DEFAULT_COMPLETION_THRESHOLD = 0.92f

        fun keyFor(mediaId: MediaId, seasonNumber: Int?, episodeNumber: Int?): String =
            if (seasonNumber != null && episodeNumber != null) {
                mediaId.value + "#" + seasonNumber + "x" + episodeNumber
            } else {
                mediaId.value
            }
    }
}

/** A row on the Continue Watching rail: the title, plus where the user got to. */
data class ContinueWatchingItem(
    val item: MediaItem,
    val progress: PlaybackProgress,
    /** Populated for episodes so the card can say "S02E04 · Decoherence". */
    val episodeLabel: String? = null,
)

/** One viewing, for the History screen. Kept even after the title is finished. */
data class HistoryEntry(
    val item: MediaItem,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val watchedAt: Instant,
    val positionMillis: Long,
    val durationMillis: Long,
    val completed: Boolean,
)

/**
 * A favourite. Films and shows use [MediaId]; IPTV channels use their channel id. One type keeps
 * the Favourites screen a single list rather than two parallel implementations.
 */
sealed interface Favourite {
    data class Title(val id: MediaId) : Favourite
    data class LiveChannel(val channelId: String, val playlistId: String) : Favourite

    val storageKey: String
        get() = when (this) {
            is Title -> "title:" + id.value
            is LiveChannel -> "channel:" + playlistId + ":" + channelId
        }
}
