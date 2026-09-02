package com.sustream.tv.data.tmdb

import com.sustream.tv.domain.repository.ImageUrlBuilder
import java.util.concurrent.atomic.AtomicReference

/**
 * Builds TMDB image URLs.
 *
 * Two reasons this is not a string template:
 *
 *  1. **The base URL and the available sizes come from TMDB's `/configuration` endpoint.** The
 *     brief requires image URL construction through a configurable base, and TMDB has retired size
 *     buckets before — a hard-coded `w500` becomes a 404 the day that happens. [update] installs
 *     the fetched configuration; [FALLBACK_CONFIG] covers the case where the call has not completed
 *     or failed.
 *  2. **Picking the right size matters more on a TV than anywhere else.** A poster card is 132 dp
 *     wide, which is 264 px on a 1080p panel and 528 px on a 4K one. Fetching `original` for a
 *     264 px slot wastes bandwidth and memory on a device with 1 GB of RAM; fetching `w92` for a
 *     4K panel looks soft. [pick] chooses the smallest bucket that is at least as wide as the slot.
 *
 * Thread-safe: the configuration is read from composables on the main thread and written from a
 * background refresh, so it is held in an [AtomicReference].
 */
class TmdbImageUrlBuilder(
    private val fallbackBaseUrl: String,
) : ImageUrlBuilder {

    private val config = AtomicReference(FALLBACK_CONFIG)

    /** Installs configuration fetched from TMDB. Ignores an obviously unusable response. */
    internal fun update(dto: TmdbImageConfigDto) {
        if (dto.secureBaseUrl.isBlank()) return
        config.set(
            ImageConfig(
                baseUrl = dto.secureBaseUrl,
                posterSizes = dto.posterSizes.ifEmpty { FALLBACK_CONFIG.posterSizes },
                backdropSizes = dto.backdropSizes.ifEmpty { FALLBACK_CONFIG.backdropSizes },
                stillSizes = dto.stillSizes.ifEmpty { FALLBACK_CONFIG.stillSizes },
                profileSizes = dto.profileSizes.ifEmpty { FALLBACK_CONFIG.profileSizes },
            ),
        )
    }

    override fun poster(path: String?, targetWidthPx: Int): String? =
        build(path, config.get().posterSizes, targetWidthPx)

    override fun backdrop(path: String?, targetWidthPx: Int): String? =
        build(path, config.get().backdropSizes, targetWidthPx)

    override fun still(path: String?, targetWidthPx: Int): String? =
        build(path, config.get().stillSizes, targetWidthPx)

    override fun profile(path: String?, targetWidthPx: Int): String? =
        build(path, config.get().profileSizes, targetWidthPx)

    private fun build(path: String?, sizes: List<String>, targetWidthPx: Int): String? {
        if (path.isNullOrBlank()) return null
        val size = pick(sizes, targetWidthPx)
        val base = config.get().baseUrl.ifBlank { fallbackBaseUrl }
        // TMDB paths always start with "/", so the join must not add a second slash.
        val normalisedPath = if (path.startsWith('/')) path else "/$path"
        return base.trimEnd('/') + "/" + size + normalisedPath
    }

    /**
     * Chooses the smallest bucket at least [targetWidthPx] wide.
     *
     * Buckets look like `w92`, `w500`, `h632` or `original`. Height-keyed and `original` entries are
     * only used when nothing width-keyed is large enough.
     */
    internal fun pick(sizes: List<String>, targetWidthPx: Int): String {
        if (sizes.isEmpty()) return ORIGINAL

        val widthBuckets = sizes
            .mapNotNull { size ->
                size.removePrefix("w").toIntOrNull()?.let { width -> width to size }
            }
            .sortedBy { it.first }

        val chosen = widthBuckets.firstOrNull { it.first >= targetWidthPx }?.second
        if (chosen != null) return chosen

        // Nothing wide enough: take the largest width bucket, or `original` if TMDB offers it.
        return when {
            sizes.contains(ORIGINAL) -> ORIGINAL
            widthBuckets.isNotEmpty() -> widthBuckets.last().second
            else -> sizes.last()
        }
    }

    internal data class ImageConfig(
        val baseUrl: String,
        val posterSizes: List<String>,
        val backdropSizes: List<String>,
        val stillSizes: List<String>,
        val profileSizes: List<String>,
    )

    companion object {
        private const val ORIGINAL = "original"

        /**
         * TMDB's documented buckets as of writing.
         *
         * Used only until `/configuration` succeeds, and refreshed every 24 hours thereafter. If
         * TMDB changes its buckets, the fetched configuration takes over and these become dead
         * weight rather than a source of 404s.
         */
        internal val FALLBACK_CONFIG = ImageConfig(
            baseUrl = "https://image.tmdb.org/t/p/",
            posterSizes = listOf("w92", "w154", "w185", "w342", "w500", "w780", ORIGINAL),
            backdropSizes = listOf("w300", "w780", "w1280", ORIGINAL),
            stillSizes = listOf("w92", "w185", "w300", ORIGINAL),
            profileSizes = listOf("w45", "w185", "h632", ORIGINAL),
        )

        /** How long a fetched configuration is trusted before being refreshed. */
        const val CONFIG_TTL_MILLIS = 24L * 60 * 60 * 1000
    }
}
