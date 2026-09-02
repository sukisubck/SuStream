package com.sustream.tv.iptv

import com.sustream.tv.core.log.AppLog
import com.sustream.tv.core.log.Secret
import com.sustream.tv.core.net.UrlValidator
import com.sustream.tv.core.net.safeApiCall
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.core.result.getOrNull
import com.sustream.tv.core.util.DispatcherProvider
import com.sustream.tv.core.util.TimeSource
import com.sustream.tv.data.local.dao.IptvDao
import com.sustream.tv.data.local.dao.LibraryDao
import com.sustream.tv.data.local.toDomain
import com.sustream.tv.data.local.toEntity
import com.sustream.tv.data.prefs.SecureCredentialStore
import com.sustream.tv.domain.model.Channel
import com.sustream.tv.domain.model.ChannelCategory
import com.sustream.tv.domain.model.Favourite
import com.sustream.tv.domain.model.ParseProblem
import com.sustream.tv.domain.model.Playlist
import com.sustream.tv.domain.model.PlaylistOrigin
import com.sustream.tv.domain.model.PlaylistParseReport
import com.sustream.tv.domain.model.PlaylistStatus
import com.sustream.tv.domain.repository.IptvPlaylistRepository
import com.sustream.tv.domain.repository.SettingsRepository
import com.sustream.tv.iptv.m3u.M3uEntry
import com.sustream.tv.iptv.m3u.M3uParser
import com.sustream.tv.iptv.xtream.XtreamClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.UUID

private const val TAG = "Iptv"

/**
 * User-supplied IPTV playlists.
 *
 * The app ships no channel lists and performs no provider discovery: everything here operates on a
 * service the user configured with their own subscription details.
 *
 * Credentials never touch this class's persisted state. An Xtream password goes straight into
 * [SecureCredentialStore] keyed by playlist id, and is read back only for the moment a request is
 * made — so a `Playlist` can be logged, cached or handed to the UI without leaking a subscription.
 */
class IptvPlaylistRepositoryImpl(
    private val iptvDao: IptvDao,
    private val libraryDao: LibraryDao,
    private val fetcher: PlaylistFetcher,
    private val xtreamClient: XtreamClient,
    private val credentialStore: SecureCredentialStore,
    private val settingsRepository: SettingsRepository,
    private val urlValidator: UrlValidator,
    private val probeClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
    private val timeSource: TimeSource,
) : IptvPlaylistRepository {

    override fun observePlaylists(): Flow<List<Playlist>> =
        iptvDao.observePlaylists().map { rows -> rows.mapNotNull { it.toDomain() } }

    /**
     * Channels, with favourite state folded in.
     *
     * Combined from two flows rather than joined in SQL so that toggling a favourite updates the
     * list without re-reading several thousand channel rows.
     */
    override fun observeChannels(playlistId: String?, category: String): Flow<List<Channel>> {
        val channels = when (category) {
            ChannelCategory.ALL -> iptvDao.observeChannels(playlistId)
            ChannelCategory.UNGROUPED -> iptvDao.observeChannelsInGroup(playlistId, null)
            else -> iptvDao.observeChannelsInGroup(playlistId, category)
        }
        return channels.combine(libraryDao.observeFavouriteChannelIds()) { rows, favourites ->
            val favouriteSet = favourites.toSet()
            rows.map { it.toDomain(isFavourite = favouriteSet.contains(it.id)) }
        }
    }

    override fun observeCategories(playlistId: String?): Flow<List<ChannelCategory>> =
        iptvDao.observeCategories(playlistId).map { counts -> counts.map { it.toDomain() } }

    override fun observeFavouriteChannels(): Flow<List<Channel>> =
        libraryDao.observeFavouriteChannelIds().combine(
            iptvDao.observeChannels(null),
        ) { favouriteIds, allChannels ->
            val favouriteSet = favouriteIds.toSet()
            allChannels
                .filter { favouriteSet.contains(it.id) }
                .map { it.toDomain(isFavourite = true) }
        }

    override suspend fun channelById(channelId: String): AppResult<Channel> =
        withContext(dispatchers.io) {
            val entity = iptvDao.channel(channelId)
                ?: return@withContext AppResult.Failure(
                    AppError.NotFound("That channel is no longer in your playlists."),
                )
            val favourite = libraryDao.isFavourite(
                Favourite.LiveChannel(channelId, entity.playlistId).storageKey,
            )
            AppResult.Success(entity.toDomain(isFavourite = favourite))
        }

    override suspend fun searchChannels(query: String, limit: Int): AppResult<List<Channel>> =
        withContext(dispatchers.io) {
            val trimmed = query.trim()
            if (trimmed.length < MIN_SEARCH_LENGTH) return@withContext AppResult.Success(emptyList())
            try {
                // Escape the LIKE wildcards, or a user typing "%" matches every channel.
                val escaped = trimmed.replace("%", "").replace("_", "")
                AppResult.Success(
                    iptvDao.searchChannels(escaped, limit).map { it.toDomain() },
                )
            } catch (throwable: Throwable) {
                AppResult.Failure(AppError.Storage("Channels could not be searched."))
            }
        }

    override suspend fun addPlaylist(
        name: String,
        origin: PlaylistOrigin,
        epgUrl: String?,
        password: Secret?,
        cleartextAcknowledged: Boolean,
    ): AppResult<Playlist> = withContext(dispatchers.io) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            return@withContext AppResult.Failure(
                AppError.Unknown("Give the playlist a name so you can tell it apart later."),
            )
        }

        val allowPrivate = settingsRepository.current().iptv.allowLocalNetworkPlaylists

        // Validate before anything is written, so a rejected URL leaves no half-created playlist.
        val urlToCheck = when (origin) {
            is PlaylistOrigin.M3uUrl -> origin.url
            is PlaylistOrigin.Xtream -> origin.serverUrl
            is PlaylistOrigin.M3uFile -> null
        }
        if (urlToCheck != null) {
            val check = urlValidator.validate(
                raw = urlToCheck,
                usage = UrlValidator.Usage.USER_MEDIA,
                options = UrlValidator.Options(cleartextAcknowledged, allowPrivate),
            )
            if (check is AppResult.Failure) return@withContext check
        }

        if (origin is PlaylistOrigin.Xtream && (password == null || password.isBlank)) {
            return@withContext AppResult.Failure(
                AppError.Unauthorised("A password is required for this kind of provider.", false),
            )
        }

        val id = UUID.randomUUID().toString()
        val now = timeSource.now()

        val playlist = Playlist(
            id = id,
            name = trimmedName,
            origin = origin,
            channelCount = 0,
            lastSyncedAt = null,
            status = PlaylistStatus.NEVER_SYNCED,
            epgUrl = epgUrl?.trim()?.takeIf { it.isNotEmpty() },
            cleartextAcknowledged = cleartextAcknowledged,
            lastErrorDetail = null,
            createdAt = now,
        )

        try {
            iptvDao.upsertPlaylist(playlist.toEntity())
            if (origin is PlaylistOrigin.Xtream && password != null) {
                credentialStore.putPlaylistPassword(id, password)
            }
        } catch (throwable: Throwable) {
            AppLog.e(TAG, "Could not create playlist", throwable)
            return@withContext AppResult.Failure(
                AppError.Storage("The playlist could not be saved."),
            )
        }

        // First sync happens immediately: a playlist that appears in the list with no channels and
        // no explanation is worse than one that reports why it failed.
        when (val sync = refreshPlaylist(id)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> AppLog.w(TAG, "First sync failed for " + id + ": " + sync.error)
        }

        val stored = iptvDao.playlist(id)?.toDomain()
        AppResult.Success(stored ?: playlist)
    }

    override suspend fun renamePlaylist(playlistId: String, name: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                return@withContext AppResult.Failure(AppError.Unknown("The name cannot be empty."))
            }
            iptvDao.renamePlaylist(playlistId, trimmed)
            AppResult.Success(Unit)
        }

    override suspend fun updateXtreamCredentials(
        playlistId: String,
        username: String,
        password: Secret,
    ): AppResult<Unit> = withContext(dispatchers.io) {
        iptvDao.updateXtreamUsername(playlistId, username.trim())
        credentialStore.putPlaylistPassword(playlistId, password)
        AppResult.Success(Unit)
    }

    override suspend fun updateEpgUrl(playlistId: String, epgUrl: String?): AppResult<Unit> =
        withContext(dispatchers.io) {
            val trimmed = epgUrl?.trim()?.takeIf { it.isNotEmpty() }
            if (trimmed != null) {
                val allowPrivate = settingsRepository.current().iptv.allowLocalNetworkPlaylists
                val playlist = iptvDao.playlist(playlistId)?.toDomain()
                val check = urlValidator.validate(
                    raw = trimmed,
                    usage = UrlValidator.Usage.USER_MEDIA,
                    options = UrlValidator.Options(
                        cleartextAcknowledged = playlist?.cleartextAcknowledged == true,
                        allowPrivateHosts = allowPrivate,
                    ),
                )
                if (check is AppResult.Failure) return@withContext check
            }
            iptvDao.updateEpgUrl(playlistId, trimmed)
            AppResult.Success(Unit)
        }

    /**
     * Re-fetches and re-parses a playlist.
     *
     * Returns the parse report rather than bare success: a partially readable playlist is the
     * common case, and the brief requires parse failures to be shown clearly. One malformed line
     * should not discard 1,400 working channels, and the user is entitled to know it happened.
     */
    override suspend fun refreshPlaylist(playlistId: String): AppResult<PlaylistParseReport> =
        withContext(dispatchers.io) {
            val playlist = iptvDao.playlist(playlistId)?.toDomain()
                ?: return@withContext AppResult.Failure(
                    AppError.NotFound("That playlist no longer exists."),
                )

            val allowPrivate = settingsRepository.current().iptv.allowLocalNetworkPlaylists

            val outcome = when (val origin = playlist.origin) {
                is PlaylistOrigin.M3uUrl -> fetchFromM3uUrl(origin, playlist, allowPrivate)
                is PlaylistOrigin.M3uFile -> fetchFromDocument(origin)
                is PlaylistOrigin.Xtream -> fetchFromXtream(origin, playlist, allowPrivate)
            }

            when (outcome) {
                is AppResult.Failure -> {
                    persistFailure(playlistId, outcome.error, playlist.channelCount)
                    outcome
                }

                is AppResult.Success -> {
                    val fetched = outcome.value
                    val validation = toChannels(playlistId, fetched.entries, allowPrivate, playlist)

                    // Two independent sources of skipped lines: the M3U parse itself, and channels
                    // whose stream address failed validation. The user should see both counts.
                    val report = validation.copy(
                        skippedLineCount = validation.skippedLineCount + fetched.skippedLineCount,
                        problems = (fetched.problems + validation.problems)
                            .take(PlaylistParseReport.MAX_REPORTED_PROBLEMS),
                        truncated = fetched.truncated,
                    )

                    if (report.isEmpty) {
                        val error = AppError.ParseFailed(
                            "No channels could be read from this playlist.",
                        )
                        persistFailure(playlistId, error, 0)
                        return@withContext AppResult.Failure(error)
                    }

                    iptvDao.replaceChannels(playlistId, report.channels.map { it.toEntity() })
                    iptvDao.updatePlaylistStatus(
                        id = playlistId,
                        status = PlaylistStatus.OK.name,
                        detail = null,
                        syncedAt = timeSource.nowMillis(),
                        channelCount = report.channels.size,
                    )

                    // Adopt the provider's own EPG endpoint if we did not already have one. This is
                    // lawful: it is the provider telling us where their guide lives.
                    if (playlist.epgUrl == null && fetched.epgUrl != null) {
                        iptvDao.updateEpgUrl(playlistId, fetched.epgUrl)
                    }

                    AppResult.Success(report)
                }
            }
        }

    private suspend fun fetchFromM3uUrl(
        origin: PlaylistOrigin.M3uUrl,
        playlist: Playlist,
        allowPrivate: Boolean,
    ): AppResult<FetchedPlaylist> {
        val text = fetcher.fetchText(
            url = origin.url,
            cleartextAcknowledged = playlist.cleartextAcknowledged,
            allowPrivateHosts = allowPrivate,
        )
        return when (text) {
            is AppResult.Failure -> text
            is AppResult.Success -> parseM3u(text.value)
        }
    }

    private suspend fun fetchFromDocument(
        origin: PlaylistOrigin.M3uFile,
    ): AppResult<FetchedPlaylist> = when (val text = fetcher.readDocument(origin.documentUri)) {
        is AppResult.Failure -> text
        is AppResult.Success -> parseM3u(text.value)
    }

    private fun parseM3u(content: String): AppResult<FetchedPlaylist> {
        // A 200-status HTML "subscription expired" page is the single most common failure mode, and
        // without this check it surfaces as a baffling parse error instead of a clear message.
        if (!M3uParser.looksLikePlaylist(content)) {
            return AppResult.Failure(
                AppError.ParseFailed(
                    "That address returned a web page rather than a playlist. Check the URL, or " +
                        "whether the subscription is still active.",
                ),
            )
        }
        val parsed = M3uParser.parse(content)
        return AppResult.Success(
            FetchedPlaylist(
                entries = parsed.entries,
                epgUrl = parsed.epgUrl,
                skippedLineCount = parsed.skippedLineCount,
                problems = parsed.problems.map { ParseProblem(it.lineNumber, it.message) },
                truncated = parsed.truncated,
            ),
        )
    }

    private suspend fun fetchFromXtream(
        origin: PlaylistOrigin.Xtream,
        playlist: Playlist,
        allowPrivate: Boolean,
    ): AppResult<FetchedPlaylist> {
        val password = credentialStore.playlistPassword(playlist.id)
            ?: return AppResult.Failure(
                AppError.Unauthorised(
                    if (credentialStore.isAvailable) {
                        "The stored password for this provider is missing. Enter it again."
                    } else {
                        "Secure storage is unavailable on this device, so the password could not " +
                            "be kept. Enter it again."
                    },
                    refreshable = false,
                ),
            )

        val channels = xtreamClient.fetchChannels(
            serverUrl = origin.serverUrl,
            username = origin.username,
            password = password,
            cleartextAcknowledged = playlist.cleartextAcknowledged,
            allowPrivateHosts = allowPrivate,
        )

        return when (channels) {
            is AppResult.Failure -> channels
            is AppResult.Success -> AppResult.Success(
                FetchedPlaylist(
                    entries = channels.value.entries,
                    // Fall back to the provider's conventional XMLTV endpoint only when the export
                    // did not advertise one.
                    epgUrl = channels.value.epgUrl ?: xtreamClient.epgUrlFor(
                        origin.serverUrl,
                        origin.username,
                        password,
                    ),
                    skippedLineCount = 0,
                    problems = emptyList(),
                    truncated = false,
                ),
            )
        }
    }

    /**
     * Converts parsed entries to domain channels, validating every stream URL.
     *
     * This is the point where a hostile playlist is stopped: an entry pointing at
     * `file:///data/data/...` or `javascript:` is dropped and reported, not stored.
     */
    private fun toChannels(
        playlistId: String,
        entries: List<M3uEntry>,
        allowPrivate: Boolean,
        playlist: Playlist,
    ): PlaylistParseReport {
        val channels = mutableListOf<Channel>()
        val problems = mutableListOf<ParseProblem>()
        var skipped = 0
        val seenIds = mutableSetOf<String>()

        entries.forEachIndexed { index, entry ->
            val check = urlValidator.validate(
                raw = entry.streamUrl,
                usage = UrlValidator.Usage.USER_MEDIA,
                options = UrlValidator.Options(
                    cleartextAcknowledged = playlist.cleartextAcknowledged,
                    allowPrivateHosts = allowPrivate,
                ),
            )
            if (check is AppResult.Failure) {
                skipped++
                if (problems.size < PlaylistParseReport.MAX_REPORTED_PROBLEMS) {
                    problems += ParseProblem(
                        entry.sourceLineNumber,
                        entry.displayName + ": " + (check.error.detail ?: "unusable stream address"),
                    )
                }
                return@forEachIndexed
            }

            // Logos are decorative; a bad one should not cost the channel, so it is dropped rather
            // than rejecting the entry.
            val logo = entry.logoUrl?.let { candidate ->
                urlValidator
                    .validate(candidate, UrlValidator.Usage.IMAGE)
                    .getOrNull()
                    ?.value
            }

            // Deduplicate ids: providers reuse tvg-id across channels, and a duplicate primary key
            // would silently drop channels on insert.
            val baseId = entry.tvgId?.takeIf { it.isNotBlank() }
                ?: (entry.displayName + "#" + index)
            var candidateId = playlistId + ":" + baseId
            if (!seenIds.add(candidateId)) {
                candidateId = playlistId + ":" + baseId + "#" + index
                seenIds.add(candidateId)
            }

            channels += Channel(
                id = candidateId,
                playlistId = playlistId,
                number = entry.channelNumber,
                name = entry.displayName,
                logoUrl = logo,
                group = entry.group,
                tvgId = entry.tvgId?.takeIf { it.isNotBlank() },
                streamUrl = entry.streamUrl,
                isFavourite = false,
                ordinal = index,
            )
        }

        return PlaylistParseReport(
            channels = channels,
            skippedLineCount = skipped,
            problems = problems,
            truncated = false,
        )
    }

    private suspend fun persistFailure(
        playlistId: String,
        error: AppError,
        existingChannelCount: Int,
    ) {
        val status = when (error) {
            is AppError.Unauthorised -> PlaylistStatus.AUTH_FAILED
            is AppError.ParseFailed -> PlaylistStatus.PARSE_FAILED
            is AppError.Network, is AppError.Timeout -> PlaylistStatus.UNREACHABLE
            else -> PlaylistStatus.UNREACHABLE
        }
        iptvDao.updatePlaylistStatus(
            id = playlistId,
            status = status.name,
            detail = error.detail,
            // The previous sync time is preserved: the existing channel list is still what the user
            // has, and blanking it would imply the playlist had never worked.
            syncedAt = iptvDao.playlist(playlistId)?.lastSyncedAt,
            channelCount = existingChannelCount,
        )
    }

    override suspend fun deletePlaylist(playlistId: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                // Order matters: favourites reference the channels, and the credential must go even
                // if the database delete were to fail.
                libraryDao.deleteChannelFavourites(playlistId)
                iptvDao.deleteProgrammesFor(playlistId)
                iptvDao.deletePlaylist(playlistId)
                credentialStore.removePlaylistPassword(playlistId)
                AppResult.Success(Unit)
            } catch (throwable: Throwable) {
                AppLog.e(TAG, "Could not delete playlist " + playlistId, throwable)
                AppResult.Failure(AppError.Storage("The playlist could not be removed."))
            }
        }

    override suspend fun setChannelFavourite(
        channelId: String,
        favourite: Boolean,
    ): AppResult<Unit> = withContext(dispatchers.io) {
        val entity = iptvDao.channel(channelId)
            ?: return@withContext AppResult.Failure(AppError.NotFound("Unknown channel."))
        val key = Favourite.LiveChannel(channelId, entity.playlistId)
        if (favourite) {
            libraryDao.upsertFavourite(key.toEntity(timeSource.nowMillis()))
        } else {
            libraryDao.deleteFavourite(key.storageKey)
        }
        AppResult.Success(Unit)
    }

    /**
     * Confirms a channel's stream is reachable before playback starts.
     *
     * A ranged GET for the first few bytes rather than a HEAD: many IPTV servers do not implement
     * HEAD and answer it with 405, which would make every healthy channel look broken.
     */
    override suspend fun probeChannel(channelId: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            val entity = iptvDao.channel(channelId)
                ?: return@withContext AppResult.Failure(AppError.NotFound("Unknown channel."))

            safeApiCall {
                val request = Request.Builder()
                    .url(entity.streamUrl)
                    .header("Range", "bytes=0-" + (PROBE_BYTES - 1))
                    .build()

                probeClient.newCall(request).execute().use { response ->
                    // 206 Partial Content, 200, or 416 (server ignores ranges but the URL is live)
                    // all mean the stream exists.
                    val acceptable = response.isSuccessful ||
                        response.code == HTTP_RANGE_NOT_SATISFIABLE
                    if (!acceptable) {
                        throw IOException("HTTP " + response.code)
                    }
                }
            }
        }

    private data class FetchedPlaylist(
        val entries: List<M3uEntry>,
        val epgUrl: String?,
        val skippedLineCount: Int,
        val problems: List<ParseProblem>,
        val truncated: Boolean,
    )

    private companion object {
        const val MIN_SEARCH_LENGTH = 2
        const val PROBE_BYTES = 2_048
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}
