package com.sustream.tv.domain.model

import com.sustream.tv.core.log.Secret
import java.time.Instant

/**
 * IPTV models for user-supplied, authorised services.
 *
 * SuStream ships no playlists and discovers no providers. Everything here describes a service the
 * *user* configured with their own subscription details.
 */

/** How a playlist was added. Determines how it is refreshed and what credentials it needs. */
sealed interface PlaylistOrigin {

    /** A remote M3U/M3U8 URL, refreshed by re-fetching. */
    data class M3uUrl(val url: String) : PlaylistOrigin

    /**
     * A local file the user picked through the Android document picker.
     *
     * [documentUri] is a `content://` URI with a persisted read permission. It is never parsed by
     * `UrlValidator` (which rejects `content://` for exactly this reason) because it was granted
     * by the system rather than supplied as text, and it can only be refreshed by re-reading the
     * same document.
     */
    data class M3uFile(
        val documentUri: String,
        val displayName: String,
    ) : PlaylistOrigin

    /**
     * An Xtream-style provider: server URL plus username and password.
     *
     * The password is not stored here. It lives in the encrypted credential store keyed by
     * playlist id, so a `Playlist` can be logged, cached or sent to the UI without leaking it.
     * See `data.prefs.SecureCredentialStore`.
     */
    data class Xtream(
        val serverUrl: String,
        val username: String,
    ) : PlaylistOrigin

    val describedSource: String
        get() = when (this) {
            is M3uUrl -> url
            is M3uFile -> displayName
            is Xtream -> serverUrl
        }
}

enum class PlaylistStatus {
    OK,
    NEVER_SYNCED,
    /** Fetched, but the content was not a playlist we could read. */
    PARSE_FAILED,
    /** Network failure, DNS failure, timeout. */
    UNREACHABLE,
    /** The provider rejected the credentials, or the subscription has lapsed. */
    AUTH_FAILED,
}

data class Playlist(
    val id: String,
    val name: String,
    val origin: PlaylistOrigin,
    val channelCount: Int,
    val lastSyncedAt: Instant?,
    val status: PlaylistStatus,
    /** Lawful EPG endpoint, only if the playlist or provider supplied one. */
    val epgUrl: String?,
    /**
     * True when the user explicitly accepted that this playlist uses unencrypted HTTP. Recorded so
     * the warning is shown once at setup rather than on every refresh, and so `UrlValidator` knows
     * cleartext is permitted for this playlist and no other.
     */
    val cleartextAcknowledged: Boolean = false,
    /** Detail for the failure states, safe to display. Never contains credentials. */
    val lastErrorDetail: String? = null,
    val createdAt: Instant? = null,
) {
    val isUsable: Boolean get() = status == PlaylistStatus.OK && channelCount > 0
}

/** Credentials for a playlist, read from the encrypted store only when a request is being made. */
data class PlaylistCredentials(
    val username: String,
    val password: Secret,
)

data class Channel(
    /** Stable within a playlist: derived from `tvg-id` when present, otherwise name plus index. */
    val id: String,
    val playlistId: String,
    /** Channel number from the playlist, when it supplies one. Display only. */
    val number: String?,
    val name: String,
    /** From `tvg-logo`. A full URL, validated before loading. */
    val logoUrl: String?,
    /** From `group-title`. Drives the category filter. */
    val group: String?,
    /** From `tvg-id`. The join key to EPG data. */
    val tvgId: String?,
    val streamUrl: String,
    val isFavourite: Boolean = false,
    /** Position in the playlist, so the original ordering can be restored. */
    val ordinal: Int = 0,
)

/** A category derived from the playlist's own `group-title` values. Never invented by the app. */
data class ChannelCategory(
    val name: String,
    val channelCount: Int,
) {
    companion object {
        /** Synthetic "everything" bucket, used as the default filter. */
        const val ALL = "__all__"
        /** Synthetic bucket for channels the playlist left ungrouped. */
        const val UNGROUPED = "__ungrouped__"
    }
}

/** One programme from an XMLTV or provider EPG feed. */
data class EpgProgramme(
    /** Matches [Channel.tvgId]. */
    val channelTvgId: String,
    val title: String,
    val description: String?,
    val start: Instant,
    val end: Instant,
    val category: String? = null,
    val episodeLabel: String? = null,
) {
    fun isLiveAt(now: Instant): Boolean =
        !now.isBefore(start) && now.isBefore(end)

    val durationMinutes: Int
        get() = ((end.toEpochMilli() - start.toEpochMilli()) / MILLIS_PER_MINUTE).toInt()

    /** How far through the programme we are, 0f..1f. Drives the "now" bar in the guide. */
    fun progressAt(now: Instant): Float {
        val total = end.toEpochMilli() - start.toEpochMilli()
        if (total <= 0L) return 0f
        val elapsed = now.toEpochMilli() - start.toEpochMilli()
        return (elapsed.toFloat() / total).coerceIn(0f, 1f)
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}

/** Now-and-next for a channel, which is what the channel list shows. */
data class ChannelSchedule(
    val channelTvgId: String,
    val now: EpgProgramme?,
    val next: EpgProgramme?,
)

/** One channel's row in the guide grid. */
data class EpgChannelRow(
    val channel: Channel,
    val programmes: List<EpgProgramme>,
)

/**
 * Outcome of parsing a playlist. Surfaced to the user rather than swallowed, because the brief
 * requires parse failures to be shown clearly and a partially-readable playlist is common in
 * practice: one malformed line should not discard 1,400 working channels.
 */
data class PlaylistParseReport(
    val channels: List<Channel>,
    val skippedLineCount: Int,
    /** First few problems, for display. Bounded so a wholly malformed file cannot flood the UI. */
    val problems: List<ParseProblem>,
    val truncated: Boolean = false,
) {
    val isEmpty: Boolean get() = channels.isEmpty()

    companion object {
        const val MAX_REPORTED_PROBLEMS = 10
    }
}

data class ParseProblem(
    val lineNumber: Int,
    val message: String,
)
