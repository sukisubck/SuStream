package com.sustream.tv.data.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * TMDB wire types.
 *
 * Rules applied throughout, because TMDB responses are not as regular as the documentation implies:
 *
 *  * **Every optional field is nullable with a default.** TMDB omits `poster_path`, returns
 *    `release_date` as an empty string for unreleased titles, and omits `runtime` entirely for some
 *    records. Non-null fields would make a single odd record fail a whole page.
 *  * **`ignoreUnknownKeys` is set on the Json instance**, so TMDB adding a field never breaks us.
 *  * These types never leave `data.tmdb`. Screens see `domain.model` types, mapped by
 *    [TmdbMapper]. That is what allows the catalogue source to be swapped for the mock or the
 *    backend proxy without touching a screen.
 */

@Serializable
internal data class TmdbPageDto<T>(
    val page: Int = 1,
    val results: List<T> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 0,
    @SerialName("total_results") val totalResults: Int = 0,
)

/**
 * A row from a list endpoint.
 *
 * One type covers films, TV and `search/multi` rows because TMDB uses `title`/`release_date` for
 * films and `name`/`first_air_date` for TV, and a single tolerant type is simpler than three that
 * mostly overlap. `media_type` is present only on `search/multi` and `trending/all`, so the caller
 * supplies the type when it already knows it.
 */
@Serializable
internal data class TmdbListItemDto(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("media_type") val mediaType: String? = null,
    val popularity: Double? = null,
    val adult: Boolean = false,
)

@Serializable
internal data class TmdbGenreDto(
    val id: Int,
    val name: String,
)

@Serializable
internal data class TmdbGenreListDto(
    val genres: List<TmdbGenreDto> = emptyList(),
)

@Serializable
internal data class TmdbMovieDetailDto(
    val id: Int,
    val title: String = "",
    val overview: String = "",
    val tagline: String? = null,
    val homepage: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val runtime: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int = 0,
    val genres: List<TmdbGenreDto> = emptyList(),
    @SerialName("original_language") val originalLanguage: String? = null,
    val credits: TmdbCreditsDto? = null,
    @SerialName("release_dates") val releaseDates: TmdbReleaseDatesDto? = null,
)

@Serializable
internal data class TmdbTvDetailDto(
    val id: Int,
    val name: String = "",
    val overview: String = "",
    val tagline: String? = null,
    val homepage: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int = 0,
    val genres: List<TmdbGenreDto> = emptyList(),
    @SerialName("original_language") val originalLanguage: String? = null,
    val status: String? = null,
    val seasons: List<TmdbSeasonDto> = emptyList(),
    val credits: TmdbCreditsDto? = null,
    @SerialName("content_ratings") val contentRatings: TmdbContentRatingsDto? = null,
)

@Serializable
internal data class TmdbSeasonDto(
    @SerialName("season_number") val seasonNumber: Int,
    val name: String = "",
    val overview: String = "",
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
)

@Serializable
internal data class TmdbSeasonDetailDto(
    @SerialName("season_number") val seasonNumber: Int = 0,
    val name: String = "",
    val overview: String = "",
    @SerialName("air_date") val airDate: String? = null,
    val episodes: List<TmdbEpisodeDto> = emptyList(),
)

@Serializable
internal data class TmdbEpisodeDto(
    @SerialName("episode_number") val episodeNumber: Int,
    @SerialName("season_number") val seasonNumber: Int = 0,
    val name: String = "",
    val overview: String = "",
    val runtime: Int? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
)

@Serializable
internal data class TmdbCreditsDto(
    val cast: List<TmdbCastDto> = emptyList(),
)

@Serializable
internal data class TmdbCastDto(
    val id: Int,
    val name: String = "",
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    val order: Int = Int.MAX_VALUE,
)

/**
 * Age certifications for films: a per-country list, each with its own list of release entries.
 *
 * Deeply nested because TMDB models a film as having many releases per country, each with its own
 * certification, and often several are blank. [TmdbMapper] picks the first non-blank one for the
 * preferred region.
 */
@Serializable
internal data class TmdbReleaseDatesDto(
    val results: List<TmdbCountryReleaseDto> = emptyList(),
)

@Serializable
internal data class TmdbCountryReleaseDto(
    @SerialName("iso_3166_1") val country: String = "",
    @SerialName("release_dates") val releases: List<TmdbReleaseEntryDto> = emptyList(),
)

@Serializable
internal data class TmdbReleaseEntryDto(
    val certification: String = "",
    @SerialName("release_date") val releaseDate: String? = null,
)

/** Age certifications for TV: flatter than the film equivalent. */
@Serializable
internal data class TmdbContentRatingsDto(
    val results: List<TmdbContentRatingDto> = emptyList(),
)

@Serializable
internal data class TmdbContentRatingDto(
    @SerialName("iso_3166_1") val country: String = "",
    val rating: String = "",
)

/**
 * Image configuration.
 *
 * Fetched rather than hard-coded because TMDB has changed both the base URL and the available size
 * buckets in the past, and a hard-coded `w500` becomes a 404 the day they retire it.
 */
@Serializable
internal data class TmdbConfigurationDto(
    val images: TmdbImageConfigDto = TmdbImageConfigDto(),
)

@Serializable
internal data class TmdbImageConfigDto(
    @SerialName("secure_base_url") val secureBaseUrl: String = "",
    @SerialName("poster_sizes") val posterSizes: List<String> = emptyList(),
    @SerialName("backdrop_sizes") val backdropSizes: List<String> = emptyList(),
    @SerialName("still_sizes") val stillSizes: List<String> = emptyList(),
    @SerialName("profile_sizes") val profileSizes: List<String> = emptyList(),
)

/** TMDB's error envelope, returned alongside a non-2xx status. */
@Serializable
internal data class TmdbErrorDto(
    @SerialName("status_code") val statusCode: Int? = null,
    @SerialName("status_message") val statusMessage: String? = null,
    val success: Boolean? = null,
)
