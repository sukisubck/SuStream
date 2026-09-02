package com.sustream.tv.data.tmdb

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * TMDB v3 REST surface, restricted to what the app actually uses.
 *
 * Notes that matter:
 *  * Returns `Response<T>` rather than `T`, so a non-2xx status becomes a typed
 *    [com.sustream.tv.core.result.AppError] via `safeApiResponse` instead of an exception.
 *  * `include_adult=false` is applied to every search and discover call — a living-room TV app has
 *    no business surfacing adult titles, and the default is `true` on some endpoints.
 *  * `append_to_response` is used on the detail endpoints so a Details screen is one request rather
 *    than three. On a Fire TV Stick over patchy Wi-Fi that is the difference between the screen
 *    filling immediately and filling in stages.
 *  * Authentication is added by an interceptor, not here, so a token never appears in a signature.
 */
internal interface TmdbApi {

    // ---- Curated feeds ------------------------------------------------------

    /**
     * @param mediaType `movie` or `tv`.
     * @param timeWindow `day` or `week`. Week is used for rails: daily trending on a TV app churns
     *   noticeably between sessions and makes the home screen feel unstable.
     */
    @GET("trending/{mediaType}/{timeWindow}")
    suspend fun trending(
        @Path("mediaType") mediaType: String,
        @Path("timeWindow") timeWindow: String = "week",
        @Query("page") page: Int = 1,
        @Query("language") language: String = DEFAULT_LANGUAGE,
    ): Response<TmdbPageDto<TmdbListItemDto>>

    @GET("movie/popular")
    suspend fun popularMovies(
        @Query("page") page: Int = 1,
        @Query("language") language: String = DEFAULT_LANGUAGE,
        @Query("region") region: String = DEFAULT_REGION,
    ): Response<TmdbPageDto<TmdbListItemDto>>

    @GET("tv/popular")
    suspend fun popularTv(
        @Query("page") page: Int = 1,
        @Query("language") language: String = DEFAULT_LANGUAGE,
    ): Response<TmdbPageDto<TmdbListItemDto>>

    /** Backs the "New releases" rail. TMDB has no per-instance "recently added" concept. */
    @GET("movie/now_playing")
    suspend fun nowPlayingMovies(
        @Query("page") page: Int = 1,
        @Query("language") language: String = DEFAULT_LANGUAGE,
        @Query("region") region: String = DEFAULT_REGION,
    ): Response<TmdbPageDto<TmdbListItemDto>>

    @GET("tv/on_the_air")
    suspend fun onTheAirTv(
        @Query("page") page: Int = 1,
        @Query("language") language: String = DEFAULT_LANGUAGE,
    ): Response<TmdbPageDto<TmdbListItemDto>>

    @GET("movie/top_rated")
    suspend fun topRatedMovies(
        @Query("page") page: Int = 1,
        @Query("language") language: String = DEFAULT_LANGUAGE,
        @Query("region") region: String = DEFAULT_REGION,
    ): Response<TmdbPageDto<TmdbListItemDto>>

    @GET("tv/top_rated")
    suspend fun topRatedTv(
        @Query("page") page: Int = 1,
        @Query("language") language: String = DEFAULT_LANGUAGE,
    ): Response<TmdbPageDto<TmdbListItemDto>>

    // ---- Filtered browse ----------------------------------------------------

    @GET("discover/movie")
    suspend fun discoverMovies(
        @Query("page") page: Int = 1,
        @Query("with_genres") genreId: Int? = null,
        @Query("primary_release_year") year: Int? = null,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("language") language: String = DEFAULT_LANGUAGE,
        @Query("include_adult") includeAdult: Boolean = false,
        /**
         * Filters out titles with a handful of votes, which otherwise dominate a rating-sorted
         * browse with obscure entries rated 10.0 by three people.
         */
        @Query("vote_count.gte") minimumVotes: Int = MINIMUM_VOTES_FOR_DISCOVER,
    ): Response<TmdbPageDto<TmdbListItemDto>>

    @GET("discover/tv")
    suspend fun discoverTv(
        @Query("page") page: Int = 1,
        @Query("with_genres") genreId: Int? = null,
        @Query("first_air_date_year") year: Int? = null,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("language") language: String = DEFAULT_LANGUAGE,
        @Query("include_adult") includeAdult: Boolean = false,
        @Query("vote_count.gte") minimumVotes: Int = MINIMUM_VOTES_FOR_DISCOVER,
    ): Response<TmdbPageDto<TmdbListItemDto>>

    // ---- Search -------------------------------------------------------------

    /**
     * Multi-search. Person results are filtered out during mapping: on a TV, an on-screen-keyboard
     * search that lands on a person page which cannot be played is a dead end.
     */
    @GET("search/multi")
    suspend fun searchMulti(
        @Query("query") query: String,
        @Query("page") page: Int = 1,
        @Query("language") language: String = DEFAULT_LANGUAGE,
        @Query("include_adult") includeAdult: Boolean = false,
    ): Response<TmdbPageDto<TmdbListItemDto>>

    // ---- Details ------------------------------------------------------------

    @GET("movie/{id}")
    suspend fun movieDetail(
        @Path("id") id: Int,
        @Query("language") language: String = DEFAULT_LANGUAGE,
        @Query("append_to_response") append: String = "credits,release_dates",
    ): Response<TmdbMovieDetailDto>

    @GET("tv/{id}")
    suspend fun tvDetail(
        @Path("id") id: Int,
        @Query("language") language: String = DEFAULT_LANGUAGE,
        @Query("append_to_response") append: String = "credits,content_ratings",
    ): Response<TmdbTvDetailDto>

    @GET("tv/{id}/season/{seasonNumber}")
    suspend fun season(
        @Path("id") id: Int,
        @Path("seasonNumber") seasonNumber: Int,
        @Query("language") language: String = DEFAULT_LANGUAGE,
    ): Response<TmdbSeasonDetailDto>

    // ---- Reference data -----------------------------------------------------

    @GET("genre/movie/list")
    suspend fun movieGenres(
        @Query("language") language: String = DEFAULT_LANGUAGE,
    ): Response<TmdbGenreListDto>

    @GET("genre/tv/list")
    suspend fun tvGenres(
        @Query("language") language: String = DEFAULT_LANGUAGE,
    ): Response<TmdbGenreListDto>

    @GET("configuration")
    suspend fun configuration(): Response<TmdbConfigurationDto>

    companion object {
        /** UK English, matching the product's primary market. */
        const val DEFAULT_LANGUAGE = "en-GB"

        /** Affects `now_playing` and `popular`, which are release-region dependent. */
        const val DEFAULT_REGION = "GB"

        const val MINIMUM_VOTES_FOR_DISCOVER = 50

        const val MEDIA_TYPE_MOVIE = "movie"
        const val MEDIA_TYPE_TV = "tv"
    }
}
