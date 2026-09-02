package com.sustream.tv.domain.model

import java.time.LocalDate

/**
 * Catalogue models.
 *
 * Pure Kotlin — no Android imports, no serialisation annotations, no TMDB field names. TMDB DTOs
 * live in `data.tmdb` and are mapped into these, which is what lets the catalogue source be
 * swapped (live TMDB, the backend proxy, or the bundled mock) without touching a screen.
 *
 * A deliberate absence: nothing here says anything about whether a title can be *played*. Metadata
 * availability and playback availability are separate concerns, and conflating them is how an app
 * ends up implying that TMDB supplies video. See [PlayableSource].
 */

enum class MediaType {
    MOVIE,
    TV,
    ;

    /** Wire/DB form. Stable: persisted in Room and used in [MediaId]. */
    val key: String get() = if (this == MOVIE) "movie" else "tv"

    companion object {
        fun fromKey(key: String): MediaType? = when (key.lowercase()) {
            "movie" -> MOVIE
            "tv" -> TV
            else -> null
        }
    }
}

/**
 * Composite identifier, e.g. `movie:603`.
 *
 * TMDB numbers films and shows in separate sequences, so `603` is ambiguous on its own — it is
 * both *The Matrix* and a television series. Carrying the type in the identifier removes a whole
 * class of bug from watchlists, history rows and deep links, and gives a single primary-key string
 * for Room.
 */
@JvmInline
value class MediaId(val value: String) {

    val type: MediaType?
        get() = MediaType.fromKey(value.substringBefore(SEPARATOR, missingDelimiterValue = ""))

    val remoteId: Int?
        get() = value.substringAfter(SEPARATOR, missingDelimiterValue = "").toIntOrNull()

    override fun toString(): String = value

    companion object {
        private const val SEPARATOR = ':'

        fun of(type: MediaType, remoteId: Int): MediaId =
            MediaId(type.key + SEPARATOR + remoteId)

        /** Returns null rather than throwing, so malformed persisted rows are skipped not fatal. */
        fun parseOrNull(raw: String?): MediaId? {
            if (raw.isNullOrBlank()) return null
            val candidate = MediaId(raw)
            return if (candidate.type != null && candidate.remoteId != null) candidate else null
        }
    }
}

data class Genre(val id: Int, val name: String)

data class CastMember(
    val id: Int,
    val name: String,
    val character: String?,
    /** TMDB profile path, not a URL. The URL is built at render time from the image config. */
    val profilePath: String?,
    val order: Int,
)

/**
 * The card-sized view of a title: everything a rail or grid needs and nothing more.
 *
 * Keeping this separate from [MediaDetails] means a home screen with eight rails of twenty items
 * holds 160 small objects rather than 160 objects each carrying a full cast list.
 */
data class MediaItem(
    val id: MediaId,
    val type: MediaType,
    val title: String,
    /** TMDB path such as `/abc123.jpg`. Never a full URL — see `TmdbImageUrlBuilder`. */
    val posterPath: String?,
    val backdropPath: String?,
    val releaseYear: Int?,
    /**
     * 0-10. Null when the title has too few votes to be meaningful, so the UI shows nothing rather
     * than a confident-looking "10.0" derived from two ratings. See [MIN_VOTES_FOR_RATING].
     */
    val voteAverage: Double?,
    val voteCount: Int,
    val primaryGenre: String?,
    val overview: String,
) {
    companion object {
        /**
         * Below this, a TMDB average is noise. Chosen so a brand-new release with a handful of
         * votes does not display a misleading score next to an established title.
         */
        const val MIN_VOTES_FOR_RATING = 20
    }
}

data class Season(
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int,
    val posterPath: String?,
    val overview: String,
    val airDate: LocalDate?,
) {
    /** TMDB uses season 0 for specials; the UI labels it rather than showing "Season 0". */
    val isSpecials: Boolean get() = seasonNumber == 0
}

data class Episode(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val name: String,
    val overview: String,
    val runtimeMinutes: Int?,
    /** 16:9 still. Falls back to the season or show artwork when absent. */
    val stillPath: String?,
    val airDate: LocalDate?,
    val voteAverage: Double?,
) {
    /** Stable per-show key for progress and watched state. */
    val key: String get() = seasonNumber.toString() + "x" + episodeNumber
}

/** Identifies one episode of one show, for playback and progress. */
data class EpisodeRef(
    val showId: MediaId,
    val seasonNumber: Int,
    val episodeNumber: Int,
)

/** Everything the Details screen needs. Films have an empty [seasons] list. */
data class MediaDetails(
    val item: MediaItem,
    val genres: List<Genre>,
    val runtimeMinutes: Int?,
    val releaseDate: LocalDate?,
    val cast: List<CastMember>,
    val seasons: List<Season>,
    /** Age rating for the user's region, when TMDB supplies one. */
    val certification: String?,
    val originalLanguage: String?,
    val tagline: String?,
    val homepage: String?,
    /** Present only for TV. Used to label "Ended" vs "Returning". */
    val seriesStatus: String? = null,
) {
    val isSeries: Boolean get() = item.type == MediaType.TV
}
