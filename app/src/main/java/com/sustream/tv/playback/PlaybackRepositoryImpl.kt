package com.sustream.tv.playback

import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.core.util.DispatcherProvider
import com.sustream.tv.core.util.TimeSource
import com.sustream.tv.domain.model.Authorisation
import com.sustream.tv.domain.model.EpisodeRef
import com.sustream.tv.domain.model.MediaId
import com.sustream.tv.domain.model.MediaType
import com.sustream.tv.domain.model.PlayableSource
import com.sustream.tv.domain.model.ResolvedStream
import com.sustream.tv.domain.repository.AuthorisedSourceRepository
import com.sustream.tv.domain.repository.PlaybackRepository
import com.sustream.tv.domain.repository.TmdbRepository
import kotlinx.coroutines.withContext

/**
 * Resolution policy: the rules about *which* source to use and *when* to re-resolve, kept out of
 * both the adapters and the player.
 *
 * Adapters know how to talk to one provider. The player knows how to play a URI. Neither should own
 * the decisions here, and putting them in one class means they are testable with no network and no
 * ExoPlayer instance.
 */
class PlaybackRepositoryImpl(
    private val sourceRepository: AuthorisedSourceRepository,
    private val tmdbRepository: TmdbRepository,
    private val dispatchers: DispatcherProvider,
    private val timeSource: TimeSource,
) : PlaybackRepository {

    override suspend fun resolveForPlayback(
        source: PlayableSource,
        startPositionMillis: Long,
    ): AppResult<ResolvedStream> = withContext(dispatchers.io) {
        // A demo source is refused here rather than deeper down, so the message the user sees is
        // about the app having no content rather than about a network failure.
        if (source.authorisation is Authorisation.Demo) {
            return@withContext AppResult.Failure(
                AppError.UnsupportedFormat(
                    "This is demo data, so there is nothing to play. Add one of your own Live TV " +
                        "playlists, or connect a provider account you already hold.",
                ),
            )
        }

        when (val resolved = sourceRepository.resolve(source)) {
            is AppResult.Failure -> resolved
            is AppResult.Success -> {
                val stream = resolved.value

                // Guard against an adapter handing back a link that is already past its expiry,
                // which happens when a cached response is replayed.
                if (stream.isExpiredAt(timeSource.now())) {
                    return@withContext AppResult.Failure(
                        AppError.Expired("That link had already expired. Choose a source again."),
                    )
                }

                AppResult.Success(
                    stream.copy(
                        // Live streams have no meaningful resume point: joining a live channel
                        // partway through its buffer is not what the user asked for.
                        startPositionMillis = if (source.isLive) 0L else startPositionMillis,
                    ),
                )
            }
        }
    }

    override suspend fun reResolve(
        stream: ResolvedStream,
        atPositionMillis: Long,
    ): AppResult<ResolvedStream> = resolveForPlayback(stream.source, atPositionMillis)

    /**
     * The next episode, for autoplay.
     *
     * Rolls over to season n+1 when the current season ends, and returns null at the end of the
     * final season — which is the signal for the player to stop rather than loop.
     */
    override suspend fun nextEpisode(
        showId: MediaId,
        seasonNumber: Int,
        episodeNumber: Int,
    ): AppResult<EpisodeRef?> = withContext(dispatchers.io) {
        if (showId.type != MediaType.TV) {
            return@withContext AppResult.Success(null)
        }

        when (val episodes = tmdbRepository.episodes(showId, seasonNumber)) {
            is AppResult.Failure -> episodes
            is AppResult.Success -> {
                val nextInSeason = episodes.value
                    .filter { it.episodeNumber > episodeNumber }
                    .minByOrNull { it.episodeNumber }

                if (nextInSeason != null) {
                    return@withContext AppResult.Success(
                        EpisodeRef(showId, seasonNumber, nextInSeason.episodeNumber),
                    )
                }

                // End of the season: look for the first episode of the next one. The details call
                // tells us whether that season exists, so autoplay does not request a 404.
                when (val details = tmdbRepository.details(showId)) {
                    is AppResult.Failure -> details
                    is AppResult.Success -> {
                        val hasNextSeason = details.value.seasons.any {
                            it.seasonNumber == seasonNumber + 1 && it.episodeCount > 0
                        }
                        AppResult.Success(
                            if (hasNextSeason) {
                                EpisodeRef(showId, seasonNumber + 1, FIRST_EPISODE)
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val FIRST_EPISODE = 1
    }
}
