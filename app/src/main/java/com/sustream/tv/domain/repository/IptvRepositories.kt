package com.sustream.tv.domain.repository

import com.sustream.tv.core.log.Secret
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.domain.model.Channel
import com.sustream.tv.domain.model.ChannelCategory
import com.sustream.tv.domain.model.ChannelSchedule
import com.sustream.tv.domain.model.EpgChannelRow
import com.sustream.tv.domain.model.Playlist
import com.sustream.tv.domain.model.PlaylistOrigin
import com.sustream.tv.domain.model.PlaylistParseReport
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * User-supplied IPTV playlists and their channels.
 *
 * Everything here operates on services the user configured with their own subscription. The app
 * ships no playlists and performs no provider discovery.
 */
interface IptvPlaylistRepository {

    fun observePlaylists(): Flow<List<Playlist>>

    fun observeChannels(
        playlistId: String? = null,
        category: String = ChannelCategory.ALL,
    ): Flow<List<Channel>>

    fun observeCategories(playlistId: String? = null): Flow<List<ChannelCategory>>

    fun observeFavouriteChannels(): Flow<List<Channel>>

    suspend fun channelById(channelId: String): AppResult<Channel>

    suspend fun searchChannels(query: String, limit: Int = SEARCH_LIMIT): AppResult<List<Channel>>

    /**
     * Adds a playlist and performs the first sync.
     *
     * @param password supplied only for [PlaylistOrigin.Xtream]. Written straight to the encrypted
     *   store and never persisted alongside the playlist row, so the returned [Playlist] is safe
     *   to log or cache.
     * @param cleartextAcknowledged the user has been shown the HTTP warning and accepted it. Without
     *   this, an `http://` URL is rejected by `UrlValidator`.
     */
    suspend fun addPlaylist(
        name: String,
        origin: PlaylistOrigin,
        epgUrl: String? = null,
        password: Secret? = null,
        cleartextAcknowledged: Boolean = false,
    ): AppResult<Playlist>

    suspend fun renamePlaylist(playlistId: String, name: String): AppResult<Unit>

    suspend fun updateXtreamCredentials(
        playlistId: String,
        username: String,
        password: Secret,
    ): AppResult<Unit>

    suspend fun updateEpgUrl(playlistId: String, epgUrl: String?): AppResult<Unit>

    /**
     * Re-fetches and re-parses.
     *
     * Returns the parse report rather than just success, because a partially readable playlist is
     * the common case and the user is entitled to know that 12 lines were skipped.
     */
    suspend fun refreshPlaylist(playlistId: String): AppResult<PlaylistParseReport>

    /** Removes the playlist, its channels, its favourites and its stored credentials. */
    suspend fun deletePlaylist(playlistId: String): AppResult<Unit>

    suspend fun setChannelFavourite(channelId: String, favourite: Boolean): AppResult<Unit>

    /**
     * Checks that a channel's stream is actually reachable.
     *
     * Called before playback so a dead channel produces "server unreachable" rather than a bare
     * ExoPlayer error code. Cheap: a ranged GET for the first few bytes, not a full download.
     */
    suspend fun probeChannel(channelId: String): AppResult<Unit>

    companion object {
        const val SEARCH_LIMIT = 50
    }
}

/**
 * Electronic programme guide.
 *
 * Only ever populated from an EPG endpoint the playlist or provider itself supplied. The app does
 * not go looking for guide data elsewhere, so a playlist with no EPG simply has no guide and the
 * UI says so.
 */
interface EpgRepository {

    /** Now and next for a set of channels, which is what the channel list shows. */
    fun observeSchedules(channelTvgIds: List<String>): Flow<Map<String, ChannelSchedule>>

    /**
     * Guide rows for the grid, bounded to a time window.
     *
     * Windowed rather than "everything", because a full XMLTV feed for 1,400 channels over 7 days
     * is far more than a Fire TV Stick should hold in memory at once.
     */
    suspend fun grid(
        playlistId: String,
        from: Instant,
        to: Instant,
        channelLimit: Int = GRID_CHANNEL_LIMIT,
    ): AppResult<List<EpgChannelRow>>

    /** Fetches and stores the playlist's EPG. No-op when the playlist has no EPG URL. */
    suspend fun refresh(playlistId: String): AppResult<EpgRefreshReport>

    /** Drops guide data whose programmes have all finished, to keep the database bounded. */
    suspend fun pruneExpired(before: Instant): AppResult<Int>

    companion object {
        const val GRID_CHANNEL_LIMIT = 60
        /** Hours of guide shown at once in the grid. */
        const val GRID_WINDOW_HOURS = 6L
    }
}

data class EpgRefreshReport(
    val programmeCount: Int,
    val channelCount: Int,
    val skippedCount: Int,
    val windowStart: Instant?,
    val windowEnd: Instant?,
)
