package com.sustream.tv.provider.htmljson

import com.sustream.tv.core.net.UrlValidator
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.domain.model.AddonConfiguration
import com.sustream.tv.domain.model.Authorisation
import com.sustream.tv.domain.model.PlayableSource
import com.sustream.tv.domain.model.PlaybackRequest
import com.sustream.tv.domain.model.ResolvedStream
import com.sustream.tv.domain.model.StreamContainer
import com.sustream.tv.domain.repository.AddonRepository
import com.sustream.tv.domain.repository.AuthorisedSourceRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException

/**
 * Source adapter that fans out across every active user addon and merges results.
 *
 * Only HTTPS `url` stream objects are accepted. P2P/indirect transports are not modelled so they
 * cannot reach playback. No addon directory, no discovery UI — the user supplies the URL manually
 * and the [AddonManifestProbe] verifies it before it is stored.
 */
class HtmlJsonAddonSourceRepository(
    private val addonRepository: AddonRepository,
    private val urlValidator: UrlValidator,
    private val httpClient: OkHttpClient,
    private val json: kotlinx.serialization.json.Json,
) : AuthorisedSourceRepository {

    override val providerName: String = "User addons"

    override suspend fun isConfigured(): Boolean =
        addonRepository.observeActiveAddons().first().isNotEmpty()

    override suspend fun findSources(request: PlaybackRequest): AppResult<List<PlayableSource>> {
        if (request is PlaybackRequest.LiveChannel) return AppResult.Success(emptyList())

        val addons = addonRepository.observeActiveAddons().first()
        if (addons.isEmpty()) return AppResult.Success(emptyList())

        val sources = mutableListOf<PlayableSource>()
        var lastError: AppError? = null

        for (addon in addons) {
            when (val result = queryAddon(addon, request)) {
                is AppResult.Success -> sources += result.value
                is AppResult.Failure -> lastError = result.error
            }
        }

        return if (sources.isNotEmpty() || lastError == null) {
            AppResult.Success(sources)
        } else {
            AppResult.Failure(lastError)
        }
    }

    private suspend fun queryAddon(
        addon: AddonConfiguration,
        request: PlaybackRequest,
    ): AppResult<List<PlayableSource>> {
        val baseUrl = validatedBaseUrl(addon.normalisedBaseUrl)
            ?: return AppResult.Success(emptyList())

        return try {
            val api = api(baseUrl)
            val manifest = api.manifest()
            val type = streamType(request, manifest) ?: return AppResult.Success(emptyList())
            val id = requestId(request, manifest) ?: return AppResult.Success(emptyList())

            val authorisation = Authorisation.UserAddon(
                addonId = manifest.id,
                addonName = addon.displayName.ifBlank { manifest.name },
            )

            val sources = api.streams(type, id).streams.mapIndexedNotNull { index, stream ->
                val url = stream.url?.trim().orEmpty()
                if (url.isBlank() || stream.behaviorHints?.notWebReady == true || !url.startsWith("https://")) {
                    return@mapIndexedNotNull null
                }
                if (urlValidator.validate(url, UrlValidator.Usage.APP_SERVICE) is AppResult.Failure) {
                    return@mapIndexedNotNull null
                }
                PlayableSource(
                    id = "htmljson:" + manifest.id + ":" + index,
                    label = stream.title ?: stream.name ?: manifest.name,
                    container = containerFor(url),
                    authorisation = authorisation,
                    providerName = authorisation.displayName,
                    isLive = false,
                    qualityLabel = null,
                    resolutionKey = url,
                )
            }
            AppResult.Success(sources)
        } catch (e: IOException) {
            AppResult.Failure(AppError.Network("Addon \"${addon.displayName}\" could not be reached."))
        } catch (e: Exception) {
            AppResult.Failure(AppError.ParseFailed("Addon \"${addon.displayName}\" returned an invalid response."))
        }
    }

    override suspend fun resolve(source: PlayableSource): AppResult<ResolvedStream> {
        source.authorisation as? Authorisation.UserAddon
            ?: return AppResult.Failure(
                AppError.Unauthorised("That source did not come from a user addon.", false),
            )

        val url = source.resolutionKey
        if (!url.startsWith("https://")) {
            return AppResult.Failure(
                AppError.SchemeRejected("http", "Only encrypted addon streams are supported."),
            )
        }
        val validated = urlValidator.validate(url, UrlValidator.Usage.APP_SERVICE)
        if (validated is AppResult.Failure) return validated

        return try {
            httpClient.newCall(
                Request.Builder().url(url).header("Range", "bytes=0-2047").build(),
            ).execute().use { response ->
                if (!response.isSuccessful && response.code != 416) {
                    throw IOException("HTTP " + response.code)
                }
            }
            AppResult.Success(ResolvedStream(source, url))
        } catch (e: IOException) {
            AppResult.Failure(
                AppError.Network("The stream from \"${source.providerName}\" is unavailable."),
            )
        }
    }

    private fun api(baseUrl: String): HtmlJsonAddonApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(httpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(HtmlJsonAddonApi::class.java)

    private fun validatedBaseUrl(raw: String): String? {
        val normalised = normaliseAddonBaseUrl(raw) ?: return null
        return if (urlValidator.validate(normalised, UrlValidator.Usage.APP_SERVICE) is AppResult.Success) {
            normalised
        } else null
    }

    private fun streamType(request: PlaybackRequest, manifest: HtmlJsonManifestDto): String? {
        if (!manifest.resources.any(::isStreamResource)) return null
        return when (request) {
            is PlaybackRequest.Movie ->
                "movie".takeIf { manifest.types.isEmpty() || it in manifest.types }
            is PlaybackRequest.TvEpisode ->
                "series".takeIf { manifest.types.isEmpty() || it in manifest.types }
            is PlaybackRequest.LiveChannel -> null
        }
    }

    private fun requestId(request: PlaybackRequest, manifest: HtmlJsonManifestDto): String? {
        val id = when (request) {
            is PlaybackRequest.Movie -> request.id.value
            is PlaybackRequest.TvEpisode ->
                request.ref.showId.value + ":" + request.ref.seasonNumber + ":" + request.ref.episodeNumber
            is PlaybackRequest.LiveChannel -> return null
        }
        return id.takeIf { manifest.idPrefixes.isEmpty() || manifest.idPrefixes.any(id::startsWith) }
    }

    private fun isStreamResource(element: kotlinx.serialization.json.JsonElement): Boolean = when (element) {
        is JsonPrimitive -> element.contentOrNull == "stream"
        is JsonObject    -> element["name"]?.jsonPrimitive?.contentOrNull == "stream"
        else             -> false
    }

    private fun containerFor(url: String): StreamContainer = when {
        url.contains(".m3u8", ignoreCase = true) -> StreamContainer.HLS
        url.contains(".mpd",  ignoreCase = true) -> StreamContainer.DASH
        else -> StreamContainer.PROGRESSIVE
    }
}