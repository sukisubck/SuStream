package com.sustream.tv.data.tmdb

import com.sustream.tv.core.log.AppLog
import com.sustream.tv.core.net.safeApiResponse
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.core.result.map
import com.sustream.tv.core.util.DispatcherProvider
import com.sustream.tv.core.util.TimeSource
import com.sustream.tv.domain.model.CatalogueFeed
import com.sustream.tv.domain.model.CatalogueQuery
import com.sustream.tv.domain.model.Episode
import com.sustream.tv.domain.model.Genre
import com.sustream.tv.domain.model.MediaDetails
import com.sustream.tv.domain.model.MediaId
import com.sustream.tv.domain.model.MediaItem
import com.sustream.tv.domain.model.MediaType
import com.sustream.tv.domain.model.PagedResult
import com.sustream.tv.domain.model.RailId
import com.sustream.tv.domain.repository.TmdbRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "TmdbRepo"

/**
 * TMDB-backed catalogue.
 *
 * Caching strategy is layered deliberately:
 *  * **HTTP layer** — OkHttp's disk cache handles response reuse and conditional requests. That is
 *    the right place for it, and it survives process death for free.
 *  * **This class** — caches only the two things the HTTP cache cannot help with: the genre-id to
 *    name maps (needed synchronously while mapping every list row) and the image configuration.
 *
 * There is no in-memory page cache. It would duplicate the HTTP cache, and on a 1 GB Fire TV Stick
 * holding several hundred mapped items alive across screens is a real memory cost for no benefit.
 *
 * `internal` because its constructor takes the internal [TmdbApi]. The TMDB wire surface must not
 * leak out of this package: callers depend on the [TmdbRepository] interface, which is public.
 */
internal class TmdbRepositoryImpl(
    private val api: TmdbApi,
    private val imageUrlBuilder: TmdbImageUrlBuilder,
    private val dispatchers: DispatcherProvider,
    private val timeSource: TimeSource,
) : TmdbRepository {

    private val genreMutex = Mutex()
    private var movieGenreNames: Map<Int, String> = emptyMap()
    private var tvGenreNames: Map<Int, String> = emptyMap()

    private val configMutex = Mutex()
    private var configurationFetchedAtMillis: Long = 0L

    override suspend fun feed(railId: RailId, page: Int): AppResult<PagedResult<MediaItem>> =
        withContext(dispatchers.io) {
            val type = railId.mediaType() ?: return@withContext AppResult.Failure(
                AppError.Unknown("Rail " + railId + " is not backed by the catalogue"),
            )
            val genres = genreNames(type)

            val response = when (railId) {
                RailId.TRENDING_FILMS -> api.trending(TmdbApi.MEDIA_TYPE_MOVIE, page = page)
                RailId.TRENDING_TV -> api.trending(TmdbApi.MEDIA_TYPE_TV, page = page)
                RailId.POPULAR_FILMS -> api.popularMovies(page = page)
                RailId.POPULAR_TV -> api.popularTv(page = page)
                RailId.NEW_FILMS -> api.nowPlayingMovies(page = page)
                RailId.ON_THE_AIR_TV -> api.onTheAirTv(page = page)
                RailId.CONTINUE_WATCHING, RailId.WATCHLIST -> return@withContext AppResult.Failure(
                    AppError.Unknown("Rail " + railId + " is local, not from TMDB"),
                )
            }

            safeApiResponse { response }.map { TmdbMapper.toItemPage(it, type, genres) }
        }

    override suspend fun browse(
        query: CatalogueQuery,
        page: Int,
    ): AppResult<PagedResult<MediaItem>> = withContext(dispatchers.io) {
        val genres = genreNames(query.type)

        // Curated endpoints accept no filter parameters, so anything filtered has to go through
        // `discover`. Routing that decision through the query keeps it out of the view model.
        if (!query.requiresDiscover) {
            val railId = query.toRailId()
            return@withContext if (railId != null) {
                feed(railId, page)
            } else {
                discover(query, page, genres)
            }
        }
        discover(query, page, genres)
    }

    private suspend fun discover(
        query: CatalogueQuery,
        page: Int,
        genres: Map<Int, String>,
    ): AppResult<PagedResult<MediaItem>> {
        val response = when (query.type) {
            MediaType.MOVIE -> api.discoverMovies(
                page = page,
                genreId = query.genreId,
                year = query.year,
                sortBy = query.sort.tmdbValue,
            )

            MediaType.TV -> api.discoverTv(
                page = page,
                genreId = query.genreId,
                year = query.year,
                // TMDB's `discover/tv` rejects `primary_release_date.desc`; the TV equivalent is
                // `first_air_date.desc`, so the film-oriented value is translated here.
                sortBy = query.sort.tmdbValue.replace(
                    "primary_release_date",
                    "first_air_date",
                ),
            )
        }
        return safeApiResponse { response }.map { TmdbMapper.toItemPage(it, query.type, genres) }
    }

    override suspend fun details(id: MediaId): AppResult<MediaDetails> =
        withContext(dispatchers.io) {
            val remoteId = id.remoteId
                ?: return@withContext AppResult.Failure(AppError.NotFound("Malformed id " + id))

            when (id.type) {
                MediaType.MOVIE ->
                    safeApiResponse { api.movieDetail(remoteId) }.map { TmdbMapper.toDetails(it) }

                MediaType.TV ->
                    safeApiResponse { api.tvDetail(remoteId) }.map { TmdbMapper.toDetails(it) }

                null -> AppResult.Failure(AppError.NotFound("Unknown media type in " + id))
            }
        }

    override suspend fun episodes(showId: MediaId, seasonNumber: Int): AppResult<List<Episode>> =
        withContext(dispatchers.io) {
            val remoteId = showId.remoteId
                ?: return@withContext AppResult.Failure(AppError.NotFound("Malformed id " + showId))

            safeApiResponse { api.season(remoteId, seasonNumber) }
                .map { TmdbMapper.toEpisodes(it) }
        }

    override suspend fun search(query: String, page: Int): AppResult<PagedResult<MediaItem>> =
        withContext(dispatchers.io) {
            val trimmed = query.trim()
            if (trimmed.length < MIN_SEARCH_LENGTH) {
                return@withContext AppResult.Success(PagedResult.empty())
            }

            // Both genre maps are needed because a multi-search page mixes films and shows.
            val movies = genreNames(MediaType.MOVIE)
            val tv = genreNames(MediaType.TV)

            safeApiResponse { api.searchMulti(trimmed, page) }
                .map { TmdbMapper.toSearchPage(it, movies, tv) }
        }

    override suspend fun genres(type: MediaType): AppResult<List<Genre>> =
        withContext(dispatchers.io) {
            val response = when (type) {
                MediaType.MOVIE -> api.movieGenres()
                MediaType.TV -> api.tvGenres()
            }
            safeApiResponse { response }.map { dto ->
                // Populate the id-to-name cache as a side effect: the caller wanted the list, and
                // every subsequent list mapping wants the lookup.
                cacheGenreNames(type, dto)
                TmdbMapper.toGenres(dto)
            }
        }

    /**
     * Resolves several ids concurrently.
     *
     * Failures are dropped rather than propagated: a watchlist containing one title TMDB has since
     * withdrawn should still render the other nineteen. The rail would otherwise disappear entirely
     * because of a single dead id.
     */
    override suspend fun itemsByIds(ids: List<MediaId>): AppResult<List<MediaItem>> =
        withContext(dispatchers.io) {
            if (ids.isEmpty()) return@withContext AppResult.Success(emptyList())

            val resolved = coroutineScope {
                ids.take(MAX_BATCH_IDS)
                    .map { id -> async { details(id) } }
                    .mapNotNull { it.await().let { result -> (result as? AppResult.Success)?.value } }
                    .map { it.item }
            }

            if (resolved.isEmpty() && ids.isNotEmpty()) {
                AppResult.Failure(AppError.Network("None of the saved titles could be loaded."))
            } else {
                AppResult.Success(resolved)
            }
        }

    /**
     * Refreshes the image configuration.
     *
     * Non-fatal by design: a failure leaves [TmdbImageUrlBuilder]'s documented fallback in place, so
     * artwork still loads. Rate-limited to once per TTL so a home screen that recomposes does not
     * re-request it.
     */
    override suspend fun refreshImageConfiguration(): AppResult<Unit> =
        withContext(dispatchers.io) {
            configMutex.withLock {
                val now = timeSource.nowMillis()
                val age = now - configurationFetchedAtMillis
                if (configurationFetchedAtMillis > 0L &&
                    age < TmdbImageUrlBuilder.CONFIG_TTL_MILLIS
                ) {
                    return@withLock AppResult.Success(Unit)
                }

                when (val result = safeApiResponse { api.configuration() }) {
                    is AppResult.Success -> {
                        imageUrlBuilder.update(result.value.images)
                        configurationFetchedAtMillis = now
                        AppResult.Success(Unit)
                    }

                    is AppResult.Failure -> {
                        AppLog.w(
                            TAG,
                            "Image configuration unavailable, using documented defaults: " +
                                result.error,
                        )
                        // Reported as success: artwork works, so this is not a user-facing failure.
                        AppResult.Success(Unit)
                    }
                }
            }
        }

    // ---- Genre name cache ---------------------------------------------------

    /**
     * Genre id to name, fetched once per type.
     *
     * Needed because list endpoints return `genre_ids` but not names, and a card wants to show one
     * genre. Returning an empty map on failure is correct: the card simply shows no genre rather
     * than the rail failing.
     */
    private suspend fun genreNames(type: MediaType): Map<Int, String> = genreMutex.withLock {
        val cached = if (type == MediaType.MOVIE) movieGenreNames else tvGenreNames
        if (cached.isNotEmpty()) return@withLock cached

        val response = when (type) {
            MediaType.MOVIE -> api.movieGenres()
            MediaType.TV -> api.tvGenres()
        }
        when (val result = safeApiResponse { response }) {
            is AppResult.Success -> {
                val map = result.value.genres.associate { it.id to it.name }
                if (type == MediaType.MOVIE) movieGenreNames = map else tvGenreNames = map
                map
            }

            is AppResult.Failure -> {
                AppLog.w(TAG, "Genre list unavailable for " + type + ": " + result.error)
                emptyMap()
            }
        }
    }

    private fun cacheGenreNames(type: MediaType, dto: TmdbGenreListDto) {
        val map = dto.genres.associate { it.id to it.name }
        if (type == MediaType.MOVIE) movieGenreNames = map else tvGenreNames = map
    }

    private companion object {
        /** Below this, search is noise: a single character matches most of the catalogue. */
        const val MIN_SEARCH_LENGTH = 2

        /**
         * Ceiling on a batch id resolution. A watchlist rail shows about twenty items, and firing
         * hundreds of concurrent detail requests would be both slow and rude to TMDB.
         */
        const val MAX_BATCH_IDS = 30
    }
}

/** The media type a catalogue rail is about, or null for the local rails. */
internal fun RailId.mediaType(): MediaType? = when (this) {
    RailId.TRENDING_FILMS, RailId.POPULAR_FILMS, RailId.NEW_FILMS -> MediaType.MOVIE
    RailId.TRENDING_TV, RailId.POPULAR_TV, RailId.ON_THE_AIR_TV -> MediaType.TV
    RailId.CONTINUE_WATCHING, RailId.WATCHLIST -> null
}

/** Maps an unfiltered query back onto a curated endpoint, which is cheaper than `discover`. */
internal fun CatalogueQuery.toRailId(): RailId? = when (type) {
    MediaType.MOVIE -> when (feed) {
        CatalogueFeed.TRENDING -> RailId.TRENDING_FILMS
        CatalogueFeed.POPULAR -> RailId.POPULAR_FILMS
        CatalogueFeed.NEW_RELEASES -> RailId.NEW_FILMS
        CatalogueFeed.TOP_RATED, CatalogueFeed.DISCOVER -> null
    }

    MediaType.TV -> when (feed) {
        CatalogueFeed.TRENDING -> RailId.TRENDING_TV
        CatalogueFeed.POPULAR -> RailId.POPULAR_TV
        CatalogueFeed.NEW_RELEASES -> RailId.ON_THE_AIR_TV
        CatalogueFeed.TOP_RATED, CatalogueFeed.DISCOVER -> null
    }
}
