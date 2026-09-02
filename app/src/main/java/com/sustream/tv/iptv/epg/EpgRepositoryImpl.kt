package com.sustream.tv.iptv.epg

import com.sustream.tv.core.log.AppLog
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.core.util.DispatcherProvider
import com.sustream.tv.core.util.TimeSource
import com.sustream.tv.data.local.dao.IptvDao
import com.sustream.tv.data.local.toDomain
import com.sustream.tv.data.local.toEntity
import com.sustream.tv.domain.model.ChannelSchedule
import com.sustream.tv.domain.model.EpgChannelRow
import com.sustream.tv.domain.repository.EpgRefreshReport
import com.sustream.tv.domain.repository.EpgRepository
import com.sustream.tv.domain.repository.SettingsRepository
import com.sustream.tv.iptv.PlaylistFetcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant

private const val TAG = "Epg"

/**
 * Electronic programme guide, populated only from an endpoint the playlist or provider itself
 * supplied.
 *
 * The app never goes looking for guide data elsewhere: a playlist with no EPG URL simply has no
 * guide, and the UI says so rather than filling the gap with a third-party feed the user has no
 * relationship with.
 *
 * Guide data is windowed at every stage — at parse time, in the database query, and again when
 * building grid rows — because a full XMLTV feed for 1,400 channels over a week is tens of
 * megabytes and hundreds of thousands of rows. Holding that in memory on a 1 GB Fire TV Stick is
 * not an option, and it is not needed: the guide shows six hours at a time.
 */
class EpgRepositoryImpl(
    private val iptvDao: IptvDao,
    private val fetcher: PlaylistFetcher,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    private val timeSource: TimeSource,
) : EpgRepository {

    /**
     * Now and next for a set of channels.
     *
     * One query for everything not yet finished, then grouped in memory. That is cheaper than a
     * per-channel query: a channel list of 200 rows would otherwise be 200 round trips to SQLite on
     * every recomposition.
     */
    override fun observeSchedules(channelTvgIds: List<String>): Flow<Map<String, ChannelSchedule>> {
        if (channelTvgIds.isEmpty()) return flowOf(emptyMap())

        val now = timeSource.nowMillis()
        return iptvDao.observeUpcoming(channelTvgIds.take(MAX_OBSERVED_CHANNELS), now)
            .map { rows ->
                val nowInstant = timeSource.now()
                rows
                    .map { it.toDomain() }
                    .groupBy { it.channelTvgId }
                    .mapValues { (tvgId, programmes) ->
                        val sorted = programmes.sortedBy { it.start }
                        ChannelSchedule(
                            channelTvgId = tvgId,
                            now = sorted.firstOrNull { it.isLiveAt(nowInstant) },
                            // "Next" is the first programme starting after now, which is not
                            // necessarily the second element: the currently-live one may have been
                            // filtered out by the time filter on a slow device.
                            next = sorted.firstOrNull { it.start.isAfter(nowInstant) },
                        )
                    }
            }
    }

    override suspend fun grid(
        playlistId: String,
        from: Instant,
        to: Instant,
        channelLimit: Int,
    ): AppResult<List<EpgChannelRow>> = withContext(dispatchers.io) {
        try {
            val channels = iptvDao.channelsFor(playlistId, channelLimit)

            if (channels.isEmpty()) return@withContext AppResult.Success(emptyList())

            val programmes = iptvDao
                .programmesInWindow(playlistId, from.toEpochMilli(), to.toEpochMilli())
                .map { it.toDomain() }
                .groupBy { it.channelTvgId }

            val rows = channels.map { channel ->
                EpgChannelRow(
                    channel = channel.toDomain(),
                    programmes = channel.tvgId
                        ?.let { programmes[it] }
                        ?.sortedBy { it.start }
                        ?: emptyList(),
                )
            }
            AppResult.Success(rows)
        } catch (throwable: Throwable) {
            AppLog.e(TAG, "Could not build guide for " + playlistId, throwable)
            AppResult.Failure(AppError.Storage("The TV guide could not be read."))
        }
    }

    override suspend fun refresh(playlistId: String): AppResult<EpgRefreshReport> =
        withContext(dispatchers.io) {
            val playlist = iptvDao.playlist(playlistId)?.toDomain()
                ?: return@withContext AppResult.Failure(
                    AppError.NotFound("That playlist no longer exists."),
                )

            val epgUrl = playlist.epgUrl
                ?: return@withContext AppResult.Success(
                    // Not an error: most playlists have no guide, and treating that as a failure
                    // would put a permanent red state on a perfectly working playlist.
                    EpgRefreshReport(
                        programmeCount = 0,
                        channelCount = 0,
                        skippedCount = 0,
                        windowStart = null,
                        windowEnd = null,
                    ),
                )

            val allowPrivate = settingsRepository.current().iptv.allowLocalNetworkPlaylists

            // Only fetch the window the guide can actually display, plus a little history so a
            // programme already in progress still appears at the left edge.
            val windowStart = timeSource.now().minusSeconds(GUIDE_HISTORY_SECONDS)
            val windowEnd = timeSource.now().plusSeconds(GUIDE_FUTURE_SECONDS)

            val opened = fetcher.openEpgStream(
                url = epgUrl,
                cleartextAcknowledged = playlist.cleartextAcknowledged,
                allowPrivateHosts = allowPrivate,
            )

            when (opened) {
                is AppResult.Failure -> opened
                is AppResult.Success -> opened.value.use { epgStream ->
                    val parsed = XmltvParser.parse(
                        input = epgStream.stream,
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                    )

                    if (parsed.fatalError != null && parsed.programmes.isEmpty()) {
                        return@use AppResult.Failure(AppError.ParseFailed(parsed.fatalError))
                    }

                    iptvDao.replaceProgrammes(
                        playlistId = playlistId,
                        programmes = parsed.programmes.map { it.toEntity(playlistId) },
                    )

                    if (parsed.truncated) {
                        AppLog.w(
                            TAG,
                            "Guide for " + playlistId + " hit the " +
                                XmltvParser.MAX_PROGRAMMES + " programme cap; later entries were " +
                                "not read",
                        )
                    }

                    AppResult.Success(
                        EpgRefreshReport(
                            programmeCount = parsed.programmes.size,
                            channelCount = parsed.channelNames.size,
                            skippedCount = parsed.skippedCount,
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                        ),
                    )
                }
            }
        }

    override suspend fun pruneExpired(before: Instant): AppResult<Int> =
        withContext(dispatchers.io) {
            try {
                AppResult.Success(iptvDao.pruneProgrammesBefore(before.toEpochMilli()))
            } catch (throwable: Throwable) {
                AppResult.Failure(AppError.Storage("Old guide data could not be removed."))
            }
        }

    private companion object {
        /**
         * Ceiling on how many channels' schedules are observed at once. A channel list is paged in
         * the UI, so anything beyond this is off screen and not worth querying.
         */
        const val MAX_OBSERVED_CHANNELS = 300

        /** An hour of history so a programme already running is not cut off at the grid's left. */
        const val GUIDE_HISTORY_SECONDS = 3_600L

        /** Two days ahead: enough for the guide plus "what's on tomorrow", without hoarding a week. */
        const val GUIDE_FUTURE_SECONDS = 172_800L
    }
}
