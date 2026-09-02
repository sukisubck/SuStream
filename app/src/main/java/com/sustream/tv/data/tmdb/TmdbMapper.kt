package com.sustream.tv.data.tmdb

import com.sustream.tv.domain.model.CastMember
import com.sustream.tv.domain.model.Episode
import com.sustream.tv.domain.model.Genre
import com.sustream.tv.domain.model.MediaDetails
import com.sustream.tv.domain.model.MediaId
import com.sustream.tv.domain.model.MediaItem
import com.sustream.tv.domain.model.MediaType
import com.sustream.tv.domain.model.PagedResult
import com.sustream.tv.domain.model.Season
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * TMDB wire types to domain models.
 *
 * Pure functions with no Android or network dependency, so the whole mapping layer is unit-tested
 * on the JVM. This is where TMDB's quirks are absorbed rather than leaked into the UI:
 *
 *  * `release_date` arrives as `""` for unannounced titles, which [parseDate] treats as absent.
 *  * `vote_average` is a real number even when only two people voted, so a rating below
 *    [MediaItem.MIN_VOTES_FOR_RATING] is dropped rather than displayed.
 *  * `search/multi` mixes films, TV and people; people are filtered out.
 *  * Cast arrives in an arbitrary order on some records, so it is sorted by billing order.
 */
internal object TmdbMapper {

    /** Genre lookup, so a card can show a genre name without a second request. */
    fun toItem(
        dto: TmdbListItemDto,
        type: MediaType,
        genreNames: Map<Int, String> = emptyMap(),
    ): MediaItem = MediaItem(
        id = MediaId.of(type, dto.id),
        type = type,
        title = displayTitle(dto, type),
        posterPath = dto.posterPath,
        backdropPath = dto.backdropPath,
        releaseYear = parseYear(releaseDateFor(dto, type)),
        voteAverage = meaningfulRating(dto.voteAverage, dto.voteCount),
        voteCount = dto.voteCount,
        primaryGenre = dto.genreIds.firstNotNullOfOrNull { genreNames[it] },
        overview = dto.overview,
    )

    /**
     * Maps a page, dropping rows that cannot be represented.
     *
     * Rows are dropped rather than the page failing: one malformed record out of twenty should cost
     * that record, not the whole rail.
     */
    fun toItemPage(
        dto: TmdbPageDto<TmdbListItemDto>,
        type: MediaType,
        genreNames: Map<Int, String> = emptyMap(),
    ): PagedResult<MediaItem> = PagedResult(
        items = dto.results.mapNotNull { row ->
            if (row.adult) return@mapNotNull null
            runCatching { toItem(row, type, genreNames) }.getOrNull()
        },
        page = dto.page,
        totalPages = dto.totalPages,
    )

    /**
     * Maps a `search/multi` page.
     *
     * Each row carries its own `media_type`, so the type is read per row rather than assumed, and
     * anything that is not a film or a show (people, collections, companies) is discarded.
     */
    fun toSearchPage(
        dto: TmdbPageDto<TmdbListItemDto>,
        movieGenres: Map<Int, String> = emptyMap(),
        tvGenres: Map<Int, String> = emptyMap(),
    ): PagedResult<MediaItem> = PagedResult(
        items = dto.results.mapNotNull { row ->
            if (row.adult) return@mapNotNull null
            val type = when (row.mediaType) {
                TmdbApi.MEDIA_TYPE_MOVIE -> MediaType.MOVIE
                TmdbApi.MEDIA_TYPE_TV -> MediaType.TV
                else -> return@mapNotNull null
            }
            val genres = if (type == MediaType.MOVIE) movieGenres else tvGenres
            runCatching { toItem(row, type, genres) }.getOrNull()
        },
        page = dto.page,
        totalPages = dto.totalPages,
    )

    fun toDetails(dto: TmdbMovieDetailDto): MediaDetails {
        val item = MediaItem(
            id = MediaId.of(MediaType.MOVIE, dto.id),
            type = MediaType.MOVIE,
            title = dto.title,
            posterPath = dto.posterPath,
            backdropPath = dto.backdropPath,
            releaseYear = parseYear(dto.releaseDate),
            voteAverage = meaningfulRating(dto.voteAverage, dto.voteCount),
            voteCount = dto.voteCount,
            primaryGenre = dto.genres.firstOrNull()?.name,
            overview = dto.overview,
        )
        return MediaDetails(
            item = item,
            genres = dto.genres.map { Genre(it.id, it.name) },
            runtimeMinutes = dto.runtime?.takeIf { it > 0 },
            releaseDate = parseDate(dto.releaseDate),
            cast = toCast(dto.credits),
            seasons = emptyList(),
            certification = pickMovieCertification(dto.releaseDates),
            originalLanguage = dto.originalLanguage,
            tagline = dto.tagline?.takeIf { it.isNotBlank() },
            homepage = dto.homepage?.takeIf { it.isNotBlank() },
        )
    }

    fun toDetails(dto: TmdbTvDetailDto): MediaDetails {
        val item = MediaItem(
            id = MediaId.of(MediaType.TV, dto.id),
            type = MediaType.TV,
            title = dto.name,
            posterPath = dto.posterPath,
            backdropPath = dto.backdropPath,
            releaseYear = parseYear(dto.firstAirDate),
            voteAverage = meaningfulRating(dto.voteAverage, dto.voteCount),
            voteCount = dto.voteCount,
            primaryGenre = dto.genres.firstOrNull()?.name,
            overview = dto.overview,
        )
        return MediaDetails(
            item = item,
            genres = dto.genres.map { Genre(it.id, it.name) },
            // TMDB gives a list of episode run times; the first is the typical length.
            runtimeMinutes = dto.episodeRunTime.firstOrNull()?.takeIf { it > 0 },
            releaseDate = parseDate(dto.firstAirDate),
            cast = toCast(dto.credits),
            seasons = dto.seasons
                // Season 0 is TMDB's specials bucket. Kept, but ordered after the real seasons so
                // "Season 1" is what the user lands on.
                .sortedBy { if (it.seasonNumber == 0) Int.MAX_VALUE else it.seasonNumber }
                .map { toSeason(it) },
            certification = pickTvCertification(dto.contentRatings),
            originalLanguage = dto.originalLanguage,
            tagline = dto.tagline?.takeIf { it.isNotBlank() },
            homepage = dto.homepage?.takeIf { it.isNotBlank() },
            seriesStatus = dto.status?.takeIf { it.isNotBlank() },
        )
    }

    fun toSeason(dto: TmdbSeasonDto): Season = Season(
        seasonNumber = dto.seasonNumber,
        name = dto.name.ifBlank { "Season " + dto.seasonNumber },
        episodeCount = dto.episodeCount,
        posterPath = dto.posterPath,
        overview = dto.overview,
        airDate = parseDate(dto.airDate),
    )

    fun toEpisodes(dto: TmdbSeasonDetailDto): List<Episode> =
        dto.episodes
            .sortedBy { it.episodeNumber }
            .map { episode ->
                Episode(
                    // `season_number` is sometimes absent on the nested episode object, so fall
                    // back to the season we asked for.
                    seasonNumber = episode.seasonNumber.takeIf { it > 0 } ?: dto.seasonNumber,
                    episodeNumber = episode.episodeNumber,
                    name = episode.name.ifBlank { "Episode " + episode.episodeNumber },
                    overview = episode.overview,
                    runtimeMinutes = episode.runtime?.takeIf { it > 0 },
                    stillPath = episode.stillPath,
                    airDate = parseDate(episode.airDate),
                    voteAverage = episode.voteAverage?.takeIf { it > 0.0 },
                )
            }

    fun toGenres(dto: TmdbGenreListDto): List<Genre> =
        dto.genres.map { Genre(it.id, it.name) }.sortedBy { it.name }

    // ---- Helpers ------------------------------------------------------------

    private fun displayTitle(dto: TmdbListItemDto, type: MediaType): String = when (type) {
        MediaType.MOVIE -> dto.title ?: dto.name ?: dto.originalTitle ?: UNTITLED
        MediaType.TV -> dto.name ?: dto.title ?: dto.originalName ?: UNTITLED
    }.ifBlank { UNTITLED }

    private fun releaseDateFor(dto: TmdbListItemDto, type: MediaType): String? = when (type) {
        MediaType.MOVIE -> dto.releaseDate ?: dto.firstAirDate
        MediaType.TV -> dto.firstAirDate ?: dto.releaseDate
    }

    private fun toCast(credits: TmdbCreditsDto?): List<CastMember> =
        credits?.cast
            ?.sortedBy { it.order }
            ?.take(MAX_CAST)
            ?.map {
                CastMember(
                    id = it.id,
                    name = it.name,
                    character = it.character?.takeIf { role -> role.isNotBlank() },
                    profilePath = it.profilePath,
                    order = it.order,
                )
            }
            ?: emptyList()

    /**
     * Drops a rating that is not statistically meaningful.
     *
     * A brand-new release with four votes averaging 10.0 would otherwise sit next to an established
     * film at 8.4 and read as better. Returning null makes the UI show no rating at all.
     */
    private fun meaningfulRating(voteAverage: Double?, voteCount: Int): Double? {
        if (voteAverage == null || voteAverage <= 0.0) return null
        if (voteCount < MediaItem.MIN_VOTES_FOR_RATING) return null
        return voteAverage
    }

    /**
     * Picks the age certification for the preferred region.
     *
     * TMDB nests film certifications per country and per release, and most entries are blank, so
     * this walks to the first non-blank one. UK first, then US as a widely-understood fallback.
     */
    private fun pickMovieCertification(dto: TmdbReleaseDatesDto?): String? {
        val results = dto?.results ?: return null
        for (region in PREFERRED_CERTIFICATION_REGIONS) {
            val certification = results
                .firstOrNull { it.country.equals(region, ignoreCase = true) }
                ?.releases
                ?.firstOrNull { it.certification.isNotBlank() }
                ?.certification
            if (!certification.isNullOrBlank()) return certification
        }
        return null
    }

    private fun pickTvCertification(dto: TmdbContentRatingsDto?): String? {
        val results = dto?.results ?: return null
        for (region in PREFERRED_CERTIFICATION_REGIONS) {
            val rating = results
                .firstOrNull { it.country.equals(region, ignoreCase = true) }
                ?.rating
            if (!rating.isNullOrBlank()) return rating
        }
        return null
    }

    /** TMDB dates are `yyyy-MM-dd`, or `""` when unknown. */
    fun parseDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        return try {
            LocalDate.parse(raw)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun parseYear(raw: String?): Int? = parseDate(raw)?.year

    private const val UNTITLED = "Untitled"
    private const val MAX_CAST = 20

    /** UK first: the product's primary market. US as a fallback most viewers still recognise. */
    private val PREFERRED_CERTIFICATION_REGIONS = listOf("GB", "US")
}
