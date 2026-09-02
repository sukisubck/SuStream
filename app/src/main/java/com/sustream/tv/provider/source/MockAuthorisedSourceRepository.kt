package com.sustream.tv.provider.source

import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.domain.model.Authorisation
import com.sustream.tv.domain.model.PlayableSource
import com.sustream.tv.domain.model.PlaybackRequest
import com.sustream.tv.domain.model.ResolvedStream
import com.sustream.tv.domain.model.StreamContainer
import com.sustream.tv.domain.repository.AuthorisedSourceRepository
import kotlinx.coroutines.delay

/**
 * Fixture source adapter.
 *
 * Exists so the whole availability and selection flow — check, list, choose, resolve, handle
 * failure — is navigable and testable with no network, no credentials and no configured playlists.
 * It is the default adapter in a fresh checkout.
 *
 * ## Two properties that keep it honest
 *
 * 1. Every source it returns carries [Authorisation.Demo], and the sources sheet labels them as
 *    demo data. Nothing here pretends to be real availability.
 * 2. [resolve] **always fails**. It does not, and will not, return a network URI. A mock that
 *    fabricated a playable URL would mean the demo flow ends in a player pointed at nothing, and
 *    would make the difference between "demo" and "real availability" invisible in testing.
 *
 * The consequence is deliberate and documented: in a fresh checkout the app is fully browsable but
 * nothing plays until the user adds a playlist or connects a provider. That is the truthful state
 * of an app that ships no content. See docs/DEFERRED_AND_RESTRICTED.md.
 *
 * @param artificialDelayMillis so the "Checking your authorised sources" state is actually visible
 *   during development. Zero in tests.
 * @param sourcesPerTitle how many demo sources to offer. Zero exercises the "no source found" path.
 */
class MockAuthorisedSourceRepository(
    private val artificialDelayMillis: Long = 0L,
    private val sourcesPerTitle: Int = DEFAULT_SOURCE_COUNT,
    private val configured: Boolean = true,
) : AuthorisedSourceRepository {

    override val providerName: String = "Demo sources"

    override suspend fun isConfigured(): Boolean = configured

    override suspend fun findSources(request: PlaybackRequest): AppResult<List<PlayableSource>> {
        if (artificialDelayMillis > 0L) delay(artificialDelayMillis)
        if (!configured || sourcesPerTitle <= 0) return AppResult.Success(emptyList())

        val label = request.displayTitle
        val sources = DEMO_VARIANTS.take(sourcesPerTitle).mapIndexed { index, variant ->
            PlayableSource(
                id = "demo-" + index + "-" + label.hashCode(),
                label = variant.label,
                container = variant.container,
                authorisation = Authorisation.Demo,
                providerName = providerName,
                isLive = request.isLive,
                // A quality label is carried here because it is *provider metadata* in the fixture,
                // which is what the real adapters supply. It is never inferred from a filename.
                qualityLabel = variant.quality,
                audioLanguage = variant.audioLanguage,
                resolutionKey = "demo",
            )
        }
        return AppResult.Success(sources)
    }

    /** Always fails, by design. See the class comment. */
    override suspend fun resolve(source: PlayableSource): AppResult<ResolvedStream> {
        if (artificialDelayMillis > 0L) delay(artificialDelayMillis)
        return AppResult.Failure(
            AppError.UnsupportedFormat(
                "This is a demo source, so there is nothing to play. SuStream ships no media: add " +
                    "one of your own Live TV playlists, or connect a provider account you hold.",
            ),
        )
    }

    private data class Variant(
        val label: String,
        val quality: String?,
        val container: StreamContainer,
        val audioLanguage: String?,
    )

    private companion object {
        const val DEFAULT_SOURCE_COUNT = 3

        /**
         * Fixture variants.
         *
         * Note the vocabulary compared with the supplied prototype, which listed `4K HDR10+ REMUX`
         * with `342 seeders` from `Real-Debrid (Cached)`. There are no seeders here, no REMUX and no
         * cache claims: those fields only exist in a torrent workflow, which this app does not
         * implement.
         */
        val DEMO_VARIANTS = listOf(
            Variant("Demo stream · 1080p", "1080p", StreamContainer.HLS, "English"),
            Variant("Demo stream · 720p", "720p", StreamContainer.HLS, "English"),
            Variant("Demo stream · adaptive", null, StreamContainer.HLS, null),
            Variant("Demo stream · 480p", "480p", StreamContainer.PROGRESSIVE, "English"),
        )
    }
}
