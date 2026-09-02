package com.sustream.tv.domain.model

/**
 * Query and result shapes for browsing the catalogue.
 */

/** Which curated list a rail or grid is showing. */
enum class CatalogueFeed {
    TRENDING,
    POPULAR,
    /** TMDB `now_playing` for films, `on_the_air` for TV. Surfaced as "New releases". */
    NEW_RELEASES,
    TOP_RATED,
    /** Filtered browse via TMDB `discover`. */
    DISCOVER,
}

enum class SortOption {
    POPULARITY_DESC,
    RATING_DESC,
    RELEASE_DATE_DESC,
    TITLE_ASC,
    ;

    /** TMDB `sort_by` value. Kept here so the mapping is in one place. */
    val tmdbValue: String
        get() = when (this) {
            POPULARITY_DESC -> "popularity.desc"
            RATING_DESC -> "vote_average.desc"
            RELEASE_DATE_DESC -> "primary_release_date.desc"
            TITLE_ASC -> "title.asc"
        }
}

/**
 * A browse query. Immutable so a screen can hold it in saved state and restore filters after
 * process death.
 */
data class CatalogueQuery(
    val type: MediaType,
    val feed: CatalogueFeed = CatalogueFeed.POPULAR,
    val genreId: Int? = null,
    val year: Int? = null,
    val sort: SortOption = SortOption.POPULARITY_DESC,
) {
    val hasFilters: Boolean get() = genreId != null || year != null

    val activeFilterCount: Int
        get() = listOfNotNull(genreId, year).size

    /**
     * Any filter means the query has to go through `discover`, because the curated endpoints
     * (`trending`, `popular`) accept no filter parameters.
     */
    val requiresDiscover: Boolean get() = hasFilters || feed == CatalogueFeed.DISCOVER
}

/** One page of results. */
data class PagedResult<T>(
    val items: List<T>,
    val page: Int,
    val totalPages: Int,
) {
    /**
     * TMDB refuses pages beyond 500 with a 400, so the client stops there rather than generating a
     * request it knows will fail.
     */
    val hasMore: Boolean get() = page < minOf(totalPages, MAX_PAGE)

    val nextPage: Int? get() = if (hasMore) page + 1 else null

    companion object {
        const val MAX_PAGE = 500

        fun <T> empty(): PagedResult<T> = PagedResult(emptyList(), page = 1, totalPages = 0)
    }
}

/** A home-screen rail. [items] may be empty with a non-null [error] when just that rail failed. */
data class HomeRail(
    val id: RailId,
    val items: List<MediaItem>,
    val error: com.sustream.tv.core.result.AppError? = null,
    val isLoading: Boolean = false,
) {
    val isEmpty: Boolean get() = items.isEmpty()
}

/**
 * The identity of a rail, so ordering and titles are data rather than a hard-coded sequence of
 * composables. Onboarding interests reorder these.
 */
enum class RailId {
    CONTINUE_WATCHING,
    WATCHLIST,
    TRENDING_FILMS,
    TRENDING_TV,
    POPULAR_FILMS,
    POPULAR_TV,
    NEW_FILMS,
    ON_THE_AIR_TV,
    ;

    /** Rails backed by local data survive with no network; catalogue rails do not. */
    val isLocal: Boolean get() = this == CONTINUE_WATCHING || this == WATCHLIST
}

/** Combined search results across the catalogue and the user's channels. */
data class SearchResults(
    val query: String,
    val films: List<MediaItem>,
    val shows: List<MediaItem>,
    val channels: List<Channel>,
) {
    val isEmpty: Boolean get() = films.isEmpty() && shows.isEmpty() && channels.isEmpty()

    val totalCount: Int get() = films.size + shows.size + channels.size

    companion object {
        fun empty(query: String = ""): SearchResults =
            SearchResults(query, emptyList(), emptyList(), emptyList())
    }
}

enum class SearchScope {
    ALL,
    FILMS,
    TV,
    CHANNELS,
}
