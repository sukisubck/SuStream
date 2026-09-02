package com.sustream.tv.data.mock

import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.domain.model.CatalogueQuery
import com.sustream.tv.domain.model.Episode
import com.sustream.tv.domain.model.Genre
import com.sustream.tv.domain.model.MediaDetails
import com.sustream.tv.domain.model.MediaId
import com.sustream.tv.domain.model.MediaItem
import com.sustream.tv.domain.model.MediaType
import com.sustream.tv.domain.model.PagedResult
import com.sustream.tv.domain.model.RailId
import com.sustream.tv.domain.repository.ImageUrlBuilder
import com.sustream.tv.domain.repository.TmdbRepository
import kotlinx.coroutines.delay

/**
 * Offline catalogue.
 *
 * Selected automatically when no TMDB credential and no backend are configured, so a fresh checkout
 * builds and runs with a fully navigable app rather than an empty screen and an error. Also the
 * catalogue used by every unit and UI test.
 *
 * @param artificialDelayMillis simulates network latency so loading and skeleton states are
 *   actually visible during development. Zero in tests, where a delay would only slow the suite.
 * @param failingRails rails that should report a failure, so the per-rail error path can be
 *   exercised without unplugging anything.
 */
class MockTmdbRepository(
    private val artificialDelayMillis: Long = 0L,
    private val failingRails: Set<RailId> = emptySet(),
) : TmdbRepository {

    override suspend fun feed(railId: RailId, page: Int): AppResult<PagedResult<MediaItem>> {
        pause()
        if (railId in failingRails) {
            return AppResult.Failure(AppError.Network("Demo failure for rail " + railId))
        }
        val items = when (railId) {
            RailId.TRENDING_FILMS -> MockCatalogue.films
            RailId.POPULAR_FILMS -> MockCatalogue.films.reversed()
            RailId.NEW_FILMS -> MockCatalogue.films.sortedByDescending { it.releaseYear }
            RailId.TRENDING_TV -> MockCatalogue.shows
            RailId.POPULAR_TV -> MockCatalogue.shows.reversed()
            RailId.ON_THE_AIR_TV -> MockCatalogue.shows.sortedByDescending { it.releaseYear }
            RailId.CONTINUE_WATCHING, RailId.WATCHLIST ->
                return AppResult.Failure(
                    AppError.Unknown("Rail " + railId + " is local, not from the catalogue"),
                )
        }
        return AppResult.Success(PagedResult(items, page = 1, totalPages = 1))
    }

    override suspend fun browse(
        query: CatalogueQuery,
        page: Int,
    ): AppResult<PagedResult<MediaItem>> {
        pause()
        val pool = when (query.type) {
            MediaType.MOVIE -> MockCatalogue.films
            MediaType.TV -> MockCatalogue.shows
        }
        // Filters are honoured so the filter UI is genuinely testable offline rather than being a
        // control that appears to do nothing.
        val genreName = query.genreId?.let { id ->
            MockCatalogue.genres.firstOrNull { it.id == id }?.name
        }
        val filtered = pool
            .filter { genreName == null || it.primaryGenre == genreName }
            .filter { query.year == null || it.releaseYear == query.year }

        return AppResult.Success(PagedResult(filtered, page = 1, totalPages = 1))
    }

    override suspend fun details(id: MediaId): AppResult<MediaDetails> {
        pause()
        return MockCatalogue.details(id)
            ?.let { AppResult.Success(it) }
            ?: AppResult.Failure(AppError.NotFound("No demo entry for " + id))
    }

    override suspend fun episodes(showId: MediaId, seasonNumber: Int): AppResult<List<Episode>> {
        pause()
        val episodes = MockCatalogue.episodes(showId, seasonNumber)
        return if (episodes.isEmpty()) {
            AppResult.Failure(AppError.NotFound("No demo episodes for " + showId))
        } else {
            AppResult.Success(episodes)
        }
    }

    override suspend fun search(query: String, page: Int): AppResult<PagedResult<MediaItem>> {
        pause()
        val results = MockCatalogue.search(query)
        return AppResult.Success(PagedResult(results, page = 1, totalPages = 1))
    }

    override suspend fun genres(type: MediaType): AppResult<List<Genre>> {
        pause()
        return AppResult.Success(MockCatalogue.genres)
    }

    override suspend fun itemsByIds(ids: List<MediaId>): AppResult<List<MediaItem>> {
        pause()
        val byId = MockCatalogue.all.associateBy { it.id }
        return AppResult.Success(ids.mapNotNull { byId[it] })
    }

    /** Nothing to fetch: the mock builder already has its configuration. */
    override suspend fun refreshImageConfiguration(): AppResult<Unit> = AppResult.Success(Unit)

    private suspend fun pause() {
        if (artificialDelayMillis > 0L) delay(artificialDelayMillis)
    }
}

/**
 * Image URLs for the offline catalogue.
 *
 * Always returns null. The fixtures carry no artwork paths, and returning a fabricated URL would
 * mean every card firing a request that cannot succeed. Null makes `RemoteImage` draw its initials
 * fallback immediately.
 */
class MockImageUrlBuilder : ImageUrlBuilder {
    override fun poster(path: String?, targetWidthPx: Int): String? = null
    override fun backdrop(path: String?, targetWidthPx: Int): String? = null
    override fun still(path: String?, targetWidthPx: Int): String? = null
    override fun profile(path: String?, targetWidthPx: Int): String? = null
}
