package com.sustream.tv.data.mock

import com.sustream.tv.domain.model.CastMember
import com.sustream.tv.domain.model.Episode
import com.sustream.tv.domain.model.Genre
import com.sustream.tv.domain.model.MediaDetails
import com.sustream.tv.domain.model.MediaId
import com.sustream.tv.domain.model.MediaItem
import com.sustream.tv.domain.model.MediaType
import com.sustream.tv.domain.model.Season
import java.time.LocalDate

/**
 * Offline fixtures.
 *
 * Purpose: the whole app must be navigable, previewable and testable with no network and no
 * credentials. Every screen, view model and UI test can run against this.
 *
 * Two deliberate choices:
 *
 *  * **Titles mirror the supplied prototype's mock data** (`Cyberpunk: Neon Syndicate`,
 *    `Dune: Echoes of Arrakis`, `Singularity Protocol`, `The Cartel Kings`) so a reviewer comparing
 *    the build against `prototype.html` sees the same catalogue.
 *  * **Artwork paths are null.** The prototype pointed at Unsplash URLs, which would need the
 *    network — defeating the point of an offline fixture — and would put third-party images in the
 *    APK's apparent content. Null paths make `RemoteImage` draw its branded initials fallback, which
 *    is what a real missing poster looks like anyway, so the fixture exercises that path too.
 */
internal object MockCatalogue {

    val genres: List<Genre> = listOf(
        Genre(28, "Action"),
        Genre(12, "Adventure"),
        Genre(16, "Animation"),
        Genre(35, "Comedy"),
        Genre(80, "Crime"),
        Genre(99, "Documentary"),
        Genre(18, "Drama"),
        Genre(14, "Fantasy"),
        Genre(9648, "Mystery"),
        Genre(878, "Science fiction"),
        Genre(53, "Thriller"),
    )

    // ---- Films --------------------------------------------------------------

    val films: List<MediaItem> = listOf(
        film(
            id = 9001,
            title = "Cyberpunk: Neon Syndicate",
            year = 2025,
            rating = 8.9,
            votes = 4_312,
            genre = "Science fiction",
            overview = "In 2099 Neo-Kyoto, a rogue bio-hacker uncovers a corporate neural " +
                "monopoly that controls the digital consciousness of forty million citizens.",
        ),
        film(
            id = 9002,
            title = "Dune: Echoes of Arrakis",
            year = 2024,
            rating = 9.2,
            votes = 12_804,
            genre = "Science fiction",
            overview = "The desert saga deepens as a reluctant heir navigates a prophesied " +
                "war across uncharted sectors.",
        ),
        film(
            id = 9003,
            title = "Shadow Operative: Void",
            year = 2025,
            rating = 7.8,
            votes = 2_190,
            genre = "Thriller",
            overview = "An elite pilot wakes inside an orbital prison module with no memory " +
                "of the treason she is accused of.",
        ),
        film(
            id = 9004,
            title = "Chronicles of Eldoria",
            year = 2024,
            rating = 8.4,
            votes = 6_551,
            genre = "Fantasy",
            overview = "Ancient runic seals shatter, unleashing primeval dragons across the " +
                "medieval realms.",
        ),
        film(
            id = 9005,
            title = "The Last Voyage",
            year = 2026,
            rating = 7.6,
            votes = 812,
            genre = "Adventure",
            overview = "A crew travels beyond the known world, where every decision could be " +
                "their last.",
        ),
        film(
            id = 9006,
            title = "Northern Lights",
            year = 2026,
            rating = 7.1,
            votes = 402,
            genre = "Drama",
            overview = "Two strangers stranded by a storm in the Arctic Circle find that the " +
                "silence says more than either of them will.",
        ),
        film(
            id = 9007,
            title = "Broken Code",
            year = 2025,
            rating = 6.9,
            votes = 1_240,
            genre = "Thriller",
            overview = "A security researcher discovers her own exploit running inside a " +
                "national payments network.",
        ),
        // Deliberately below the vote-count floor, so the "no rating shown" path is exercised.
        film(
            id = 9008,
            title = "Coastline",
            year = 2026,
            rating = 9.9,
            votes = 6,
            genre = "Documentary",
            overview = "Four seasons along a shoreline that is quietly disappearing.",
        ),
    )

    // ---- TV shows -----------------------------------------------------------

    val shows: List<MediaItem> = listOf(
        show(
            id = 8001,
            title = "Singularity Protocol",
            year = 2025,
            rating = 9.1,
            votes = 8_120,
            genre = "Drama",
            overview = "Quantum computing labs in Zurich unlock parallel-universe messaging, " +
                "with catastrophic results.",
        ),
        show(
            id = 8002,
            title = "The Cartel Kings",
            year = 2024,
            rating = 8.7,
            votes = 5_308,
            genre = "Crime",
            overview = "The rise of algorithmic smuggling corridors in South America.",
        ),
        show(
            id = 8003,
            title = "Deep Field",
            year = 2026,
            rating = 8.2,
            votes = 1_902,
            genre = "Documentary",
            overview = "The people behind the telescopes, and what they expect to find.",
        ),
        show(
            id = 8004,
            title = "Night Shift",
            year = 2026,
            rating = 7.4,
            votes = 990,
            genre = "Crime",
            overview = "One hospital, one city, and the twelve hours nobody else sees.",
        ),
    )

    val all: List<MediaItem> = films + shows

    // ---- Details ------------------------------------------------------------

    private val cast: List<CastMember> = listOf(
        CastMember(1, "Demo Performer A", "Lead role", null, 0),
        CastMember(2, "Demo Performer B", "Supporting role", null, 1),
        CastMember(3, "Demo Performer C", "Supporting role", null, 2),
        CastMember(4, "Demo Performer D", "Antagonist", null, 3),
    )

    fun details(id: MediaId): MediaDetails? {
        val item = all.firstOrNull { it.id == id } ?: return null
        val isSeries = item.type == MediaType.TV
        return MediaDetails(
            item = item,
            genres = genres.filter { it.name == item.primaryGenre }.ifEmpty { genres.take(2) },
            runtimeMinutes = if (isSeries) EPISODE_RUNTIME_MINUTES else FILM_RUNTIME_MINUTES,
            releaseDate = item.releaseYear?.let { LocalDate.of(it, 3, 14) },
            cast = cast,
            seasons = if (isSeries) seasonsFor(item) else emptyList(),
            certification = if (isSeries) "15" else "12A",
            originalLanguage = "en",
            tagline = "Demo catalogue entry",
            homepage = null,
            seriesStatus = if (isSeries) "Returning series" else null,
        )
    }

    private fun seasonsFor(item: MediaItem): List<Season> {
        val seasonCount = if (item.id.remoteId == 8001) 3 else 2
        return (1..seasonCount).map { number ->
            Season(
                seasonNumber = number,
                name = "Season " + number,
                episodeCount = EPISODES_PER_SEASON,
                posterPath = null,
                overview = "Demo season " + number + " of " + item.title + ".",
                airDate = item.releaseYear?.let { LocalDate.of(it + number - 1, 9, 1) },
            )
        }
    }

    private val episodeTitles = listOf(
        "Superposition",
        "Decoherence",
        "Entropy Spike",
        "Dark River",
        "Event Horizon",
        "Cold Start",
        "Signal Loss",
        "Terminal Velocity",
    )

    fun episodes(showId: MediaId, seasonNumber: Int): List<Episode> {
        if (all.none { it.id == showId && it.type == MediaType.TV }) return emptyList()
        return (1..EPISODES_PER_SEASON).map { number ->
            Episode(
                seasonNumber = seasonNumber,
                episodeNumber = number,
                name = episodeTitles[(seasonNumber * EPISODES_PER_SEASON + number) % episodeTitles.size],
                overview = "Demo synopsis for season " + seasonNumber + ", episode " + number + ".",
                runtimeMinutes = EPISODE_RUNTIME_MINUTES + number,
                stillPath = null,
                airDate = null,
                voteAverage = 7.5 + (number % 3) * 0.4,
            )
        }
    }

    fun search(query: String): List<MediaItem> {
        val needle = query.trim().lowercase()
        if (needle.length < 2) return emptyList()
        return all.filter { item ->
            item.title.lowercase().contains(needle) ||
                item.primaryGenre?.lowercase()?.contains(needle) == true ||
                item.overview.lowercase().contains(needle)
        }
    }

    // ---- Builders -----------------------------------------------------------

    private fun film(
        id: Int,
        title: String,
        year: Int,
        rating: Double,
        votes: Int,
        genre: String,
        overview: String,
    ) = MediaItem(
        id = MediaId.of(MediaType.MOVIE, id),
        type = MediaType.MOVIE,
        title = title,
        posterPath = null,
        backdropPath = null,
        releaseYear = year,
        voteAverage = rating.takeIf { votes >= MediaItem.MIN_VOTES_FOR_RATING },
        voteCount = votes,
        primaryGenre = genre,
        overview = overview,
    )

    private fun show(
        id: Int,
        title: String,
        year: Int,
        rating: Double,
        votes: Int,
        genre: String,
        overview: String,
    ) = MediaItem(
        id = MediaId.of(MediaType.TV, id),
        type = MediaType.TV,
        title = title,
        posterPath = null,
        backdropPath = null,
        releaseYear = year,
        voteAverage = rating.takeIf { votes >= MediaItem.MIN_VOTES_FOR_RATING },
        voteCount = votes,
        primaryGenre = genre,
        overview = overview,
    )

    private const val FILM_RUNTIME_MINUTES = 138
    private const val EPISODE_RUNTIME_MINUTES = 52
    private const val EPISODES_PER_SEASON = 8
}
