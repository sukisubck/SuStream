package com.sustream.tv.provider.source

import com.sustream.tv.core.log.AppLog
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.domain.model.PlayableSource
import com.sustream.tv.domain.model.PlaybackRequest
import com.sustream.tv.domain.model.ResolvedStream
import com.sustream.tv.domain.repository.AuthorisedSourceRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private const val TAG = "Sources"

/**
 * Queries several source adapters and merges their results.
 *
 * Behaviour that matters for the availability states the brief asks for:
 *
 *  * **Adapters are queried concurrently.** A slow playlist probe should not delay a fast one; the
 *    Details screen shows availability as soon as the last one answers rather than after the sum.
 *  * **A failing adapter does not fail the whole check.** If one playlist server is down and another
 *    is fine, the user still gets the sources that exist. The failure is only propagated when
 *    *every* configured adapter failed, which is the one case where "could not check" is the honest
 *    answer rather than "nothing found".
 *  * **Ordering is deliberate**, not arbitrary: see [rank]. Real sources come before demo ones, so a
 *    user who has configured a playlist never has a demo entry pre-selected above it.
 *  * **[resolve] dispatches to the adapter that produced the source**, matched on the authorisation
 *    it carries. That is what stops a source authorised by one mechanism being resolved by another.
 */
class CompositeAuthorisedSourceRepository(
    private val adapters: List<AuthorisedSourceRepository>,
) : AuthorisedSourceRepository {

    override val providerName: String = "Your authorised sources"

    override suspend fun isConfigured(): Boolean = adapters.any { it.isConfigured() }

    override suspend fun findSources(request: PlaybackRequest): AppResult<List<PlayableSource>> =
        coroutineScope {
            val active = adapters.filter { it.isConfigured() }
            if (active.isEmpty()) return@coroutineScope AppResult.Success(emptyList())

            val results = active
                .map { adapter -> adapter to async { adapter.findSources(request) } }
                .map { (adapter, deferred) -> adapter to deferred.await() }

            val sources = mutableListOf<PlayableSource>()
            val failures = mutableListOf<AppError>()

            results.forEach { (adapter, result) ->
                when (result) {
                    is AppResult.Success -> sources += result.value
                    is AppResult.Failure -> {
                        AppLog.w(
                            TAG,
                            "Adapter " + adapter.providerName + " failed: " + result.error,
                        )
                        failures += result.error
                    }
                }
            }

            // Only report a failure when nothing at all could be checked. Otherwise the user sees
            // the sources that do exist, which is more useful than an error.
            if (sources.isEmpty() && failures.isNotEmpty()) {
                return@coroutineScope AppResult.Failure(failures.first())
            }

            AppResult.Success(sources.sortedWith(SOURCE_ORDER))
        }

    override suspend fun resolve(source: PlayableSource): AppResult<ResolvedStream> {
        // Dispatch by asking each adapter whether it recognises the source, in the order they were
        // registered. The first that can resolve it, does.
        val candidates = adapters.filter { it.isConfigured() }
        if (candidates.isEmpty()) {
            return AppResult.Failure(
                AppError.Unauthorised(
                    "No source provider is configured on this device.",
                    refreshable = false,
                ),
            )
        }

        var lastError: AppError? = null
        for (adapter in candidates) {
            when (val result = adapter.resolve(source)) {
                is AppResult.Success -> return result
                is AppResult.Failure -> {
                    // An authorisation mismatch means "not my source", so try the next adapter. Any
                    // other failure is a real problem with the right adapter, and is returned.
                    val isMismatch = result.error is AppError.Unauthorised &&
                        result.error.detail?.contains(MISMATCH_MARKER, ignoreCase = true) == true
                    if (!isMismatch) return result
                    lastError = result.error
                }
            }
        }
        return AppResult.Failure(
            lastError ?: AppError.NotFound("That source could no longer be prepared."),
        )
    }

    private companion object {
        /**
         * Substring that marks "this source is not mine" in an adapter's rejection. Matched rather
         * than typed because it is a soft signal used only to continue the loop; a wrong guess costs
         * one extra adapter call, not correctness.
         */
        const val MISMATCH_MARKER = "did not come from"

        /**
         * Real sources first, then by quality, then alphabetically.
         *
         * The demo-last rule is the important one: with a playlist configured, a demo entry must
         * never be the pre-selected top result.
         */
        val SOURCE_ORDER: Comparator<PlayableSource> = compareBy(
            { if (it.isDemo) 1 else 0 },
            { -rank(it.qualityLabel) },
            { it.label.lowercase() },
        )

        /**
         * Orders quality labels supplied by a provider.
         *
         * Only recognised labels are ranked; anything else sorts as unknown rather than being
         * guessed at. The app never derives a quality claim from a filename.
         */
        fun rank(qualityLabel: String?): Int {
            val label = qualityLabel?.lowercase() ?: return 0
            return when {
                label.contains("2160") || label.contains("4k") -> 5
                label.contains("1440") -> 4
                label.contains("1080") -> 3
                label.contains("720") -> 2
                label.contains("576") || label.contains("480") -> 1
                else -> 0
            }
        }
    }
}
