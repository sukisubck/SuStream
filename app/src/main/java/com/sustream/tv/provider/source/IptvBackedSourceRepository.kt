package com.sustream.tv.provider.source

import com.sustream.tv.core.net.UrlValidator
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.core.result.getOrNull
import com.sustream.tv.domain.model.Authorisation
import com.sustream.tv.domain.model.Channel
import com.sustream.tv.domain.model.PlayableSource
import com.sustream.tv.domain.model.PlaybackRequest
import com.sustream.tv.domain.model.Playlist
import com.sustream.tv.domain.model.ResolvedStream
import com.sustream.tv.domain.model.StreamContainer
import com.sustream.tv.domain.repository.AuthorisedSourceRepository
import com.sustream.tv.domain.repository.IptvPlaylistRepository
import com.sustream.tv.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Finds playable sources among the user's **own** IPTV playlists.
 *
 * This is the app's one real, lawful source adapter. The authorisation basis is simple and
 * verifiable: the stream comes from a service the user configured with their own subscription
 * details, so every source it returns carries [Authorisation.UserPlaylist] naming that playlist.
 *
 * Two request shapes are handled, and they are very different in strength:
 *
 *  * **[PlaybackRequest.LiveChannel]** — exact. The channel id identifies one row in one of the
 *    user's playlists; there is nothing to guess.
 *  * **[PlaybackRequest.Movie] / [PlaybackRequest.TvEpisode]** — a *name match* against the user's
 *    channel and VOD entries. This is honestly approximate: playlist entries are free text, so
 *    "Cinema Premiere HD" carrying a given film tonight cannot be detected from its name. The match
 *    is therefore deliberately conservative (see [matches]) and the UI labels the result with the
 *    playlist it came from, so the user can see exactly what they are choosing rather than trusting
 *    a confident-looking claim.
 *
 * What this class explicitly does not do: query any index, catalogue or third party for the title.
 * It only ever looks at what the user already configured.
 */
class IptvBackedSourceRepository(
    private val playlistRepository: IptvPlaylistRepository,
    private val settingsRepository: SettingsRepository,
    private val urlValidator: UrlValidator,
) : AuthorisedSourceRepository {

    override val providerName: String = "Your Live TV playlists"

    override suspend fun isConfigured(): Boolean =
        playlistRepository.observePlaylists().first().any { it.isUsable }

    override suspend fun findSources(request: PlaybackRequest): AppResult<List<PlayableSource>> {
        val playlists = playlistRepository.observePlaylists().first().filter { it.isUsable }
        if (playlists.isEmpty()) return AppResult.Success(emptyList())

        return when (request) {
            is PlaybackRequest.LiveChannel -> findLiveChannel(request, playlists)
            is PlaybackRequest.Movie -> findByTitle(request.title, playlists)
            is PlaybackRequest.TvEpisode -> findEpisode(request, playlists)
        }
    }

    private suspend fun findLiveChannel(
        request: PlaybackRequest.LiveChannel,
        playlists: List<Playlist>,
    ): AppResult<List<PlayableSource>> {
        return when (val channel = playlistRepository.channelById(request.channelId)) {
            is AppResult.Failure -> channel
            is AppResult.Success -> {
                val playlist = playlists.firstOrNull { it.id == channel.value.playlistId }
                    ?: return AppResult.Success(emptyList())
                AppResult.Success(listOf(toSource(channel.value, playlist)))
            }
        }
    }

    private suspend fun findEpisode(
        request: PlaybackRequest.TvEpisode,
        playlists: List<Playlist>,
    ): AppResult<List<PlayableSource>> {
        // Try the fully-qualified form first (`Show S02E04`), which is how VOD entries in a playlist
        // are usually named, then fall back to the show name alone.
        val episodeTag = "S" + request.ref.seasonNumber.toString().padStart(2, '0') +
            "E" + request.ref.episodeNumber.toString().padStart(2, '0')

        val qualified = findByTitle(request.showTitle + " " + episodeTag, playlists)
        val qualifiedSources = qualified.getOrNull().orEmpty()
        if (qualifiedSources.isNotEmpty()) return qualified

        return findByTitle(request.showTitle, playlists)
    }

    private suspend fun findByTitle(
        title: String,
        playlists: List<Playlist>,
    ): AppResult<List<PlayableSource>> {
        val normalised = normalise(title)
        if (normalised.length < MIN_MATCH_LENGTH) return AppResult.Success(emptyList())

        return when (val found = playlistRepository.searchChannels(title, SEARCH_LIMIT)) {
            is AppResult.Failure -> found
            is AppResult.Success -> {
                val byId = playlists.associateBy { it.id }
                val sources = found.value
                    .filter { matches(normalised, it.name) }
                    .mapNotNull { channel ->
                        byId[channel.playlistId]?.let { toSource(channel, it) }
                    }
                    .take(MAX_SOURCES)
                AppResult.Success(sources)
            }
        }
    }

    /**
     * Conservative name match.
     *
     * Requires the normalised title to appear as a whole run inside the normalised entry name.
     * A looser match — shared words, or edit distance — produces confident nonsense: searching for
     * *"The Signal"* would return every channel with "the" in its name. Missing a real match is a
     * far better failure than offering a wrong one, because the user then simply sees "no
     * authorised source for this title", which is accurate.
     */
    internal fun matches(normalisedTitle: String, entryName: String): Boolean {
        val entry = normalise(entryName)
        if (entry.isEmpty()) return false
        return entry.contains(normalisedTitle)
    }

    /** Lower-cases, strips punctuation and collapses whitespace, so `Dune: Part Two` == `dune part two`. */
    internal fun normalise(text: String): String =
        text.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ")

    private fun toSource(channel: Channel, playlist: Playlist): PlayableSource = PlayableSource(
        id = channel.id,
        label = channel.name,
        container = containerFor(channel.streamUrl),
        authorisation = Authorisation.UserPlaylist(
            playlistId = playlist.id,
            playlistName = playlist.name,
        ),
        providerName = playlist.name,
        // Anything from a live playlist is treated as live unless the URL is clearly a file. Getting
        // this wrong in the other direction would offer a seek bar on a stream with no seekable
        // window, which reads as a broken player.
        isLive = !looksLikeFile(channel.streamUrl),
        // No quality label: a playlist name is not a quality claim, and inventing "1080p" from
        // "HD" in a channel name would be a fabrication.
        qualityLabel = null,
        resolutionKey = channel.id,
    )

    /**
     * Resolves a source to a playable stream.
     *
     * Three steps, in order: confirm the channel still exists in a playlist the user has (the
     * authorisation may have been revoked by deleting the playlist), validate the URL, and confirm
     * the server answers. Only then is a [ResolvedStream] produced.
     */
    override suspend fun resolve(source: PlayableSource): AppResult<ResolvedStream> {
        val authorisation = source.authorisation as? Authorisation.UserPlaylist
            ?: return AppResult.Failure(
                AppError.Unauthorised(
                    "That source did not come from one of your playlists.",
                    refreshable = false,
                ),
            )

        val channelResult = playlistRepository.channelById(source.resolutionKey)
        if (channelResult is AppResult.Failure) return channelResult
        val channel = (channelResult as AppResult.Success).value

        // Re-check the playlist still exists and is usable: deleting a playlist must revoke every
        // source it authorised, including one already listed on screen.
        val playlists = playlistRepository.observePlaylists().first()
        val playlist = playlists.firstOrNull { it.id == authorisation.playlistId }
            ?: return AppResult.Failure(
                AppError.Unauthorised(
                    "That playlist has been removed from this device.",
                    refreshable = false,
                ),
            )

        val allowPrivate = settingsRepository.current().iptv.allowLocalNetworkPlaylists
        val validated = urlValidator.validate(
            raw = channel.streamUrl,
            usage = UrlValidator.Usage.USER_MEDIA,
            options = UrlValidator.Options(
                cleartextAcknowledged = playlist.cleartextAcknowledged,
                allowPrivateHosts = allowPrivate,
            ),
        )
        if (validated is AppResult.Failure) return validated

        // Confirm the stream actually answers, so a dead channel produces "server unreachable"
        // rather than an opaque player error code.
        val probe = playlistRepository.probeChannel(channel.id)
        if (probe is AppResult.Failure) return probe

        return AppResult.Success(
            ResolvedStream(
                source = source,
                uri = channel.streamUrl,
                headers = emptyMap(),
                // A playlist stream URL does not expire; the subscription does, and that surfaces
                // as an auth failure on the next refresh rather than as a link expiry.
                expiresAt = null,
                subtitleTracks = emptyList(),
            ),
        )
    }

    private fun containerFor(url: String): StreamContainer {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") || path.contains(".m3u8") -> StreamContainer.HLS
            path.endsWith(".mpd") -> StreamContainer.DASH
            path.endsWith(".ts") || path.endsWith(".mp4") || path.endsWith(".mkv") ->
                StreamContainer.PROGRESSIVE

            else -> StreamContainer.UNKNOWN
        }
    }

    private fun looksLikeFile(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return FILE_EXTENSIONS.any { path.endsWith(it) }
    }

    private companion object {
        /** Below this a title match is meaningless: "It" would match half a channel list. */
        const val MIN_MATCH_LENGTH = 4
        const val SEARCH_LIMIT = 50
        const val MAX_SOURCES = 10
        val FILE_EXTENSIONS = listOf(".mp4", ".mkv", ".avi", ".m4v", ".mov", ".webm")
    }
}
