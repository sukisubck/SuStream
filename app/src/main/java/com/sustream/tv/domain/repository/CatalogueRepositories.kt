package com.sustream.tv.domain.repository

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

/**
 * Catalogue metadata: titles, artwork, cast, ratings, seasons, search and discovery.
 *
 * Implementations: `TmdbRepositoryImpl` (live TMDB or the backend proxy) and
 * `MockTmdbRepository` (bundled fixtures, used offline and in tests).
 *
 * Nothing here says anything about playback. That is [AuthorisedSourceRepository]'s job, and
 * keeping the two apart is what stops the app implying that TMDB supplies video.
 */
interface TmdbRepository {

    /** One curated rail. Returns a page so the caller can decide how many items to show. */
    suspend fun feed(
        railId: RailId,
        page: Int = 1,
    ): AppResult<PagedResult<MediaItem>>

    /** Filtered browse, used by the Films and TV grids. */
    suspend fun browse(
        query: CatalogueQuery,
        page: Int,
    ): AppResult<PagedResult<MediaItem>>

    suspend fun details(id: MediaId): AppResult<MediaDetails>

    suspend fun episodes(showId: MediaId, seasonNumber: Int): AppResult<List<Episode>>

    /**
     * Multi-search across films and TV.
     *
     * People are excluded: on a TV, typing a name on an on-screen keyboard to get a person page
     * that cannot be played is a dead end.
     */
    suspend fun search(query: String, page: Int = 1): AppResult<PagedResult<MediaItem>>

    suspend fun genres(type: MediaType): AppResult<List<Genre>>

    /**
     * Resolves several ids at once, for the watchlist rail.
     *
     * A batch call rather than N single calls: a 40-item watchlist would otherwise be 40 requests
     * on every home-screen open. Missing ids are omitted rather than failing the whole batch, so
     * one title withdrawn from TMDB does not blank the rail.
     */
    suspend fun itemsByIds(ids: List<MediaId>): AppResult<List<MediaItem>>

    /**
     * Warms the TMDB image configuration cache.
     *
     * Image base URL and the available size buckets come from TMDB's `/configuration` endpoint
     * rather than being hard-coded, because TMDB has changed them before. Called once at startup;
     * failure is non-fatal and falls back to documented defaults.
     */
    suspend fun refreshImageConfiguration(): AppResult<Unit>
}

/**
 * Builds image URLs from TMDB paths.
 *
 * Separate from [TmdbRepository] because it is called from composables during layout, where a
 * suspending call is not available, and because it needs to pick a size from the target width.
 */
interface ImageUrlBuilder {

    /** @param targetWidthPx the width the image will actually be drawn at, in pixels. */
    fun poster(path: String?, targetWidthPx: Int): String?

    fun backdrop(path: String?, targetWidthPx: Int): String?

    fun still(path: String?, targetWidthPx: Int): String?

    fun profile(path: String?, targetWidthPx: Int): String?
}
