package com.sustream.tv.provider.stremio

import com.sustream.tv.core.net.UrlValidator
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.domain.model.Authorisation
import com.sustream.tv.domain.model.PlayableSource
import com.sustream.tv.domain.model.PlaybackRequest
import com.sustream.tv.domain.model.ResolvedStream
import com.sustream.tv.domain.model.StreamContainer
import com.sustream.tv.domain.repository.AuthorisedSourceRepository
import com.sustream.tv.domain.repository.SettingsRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException

/**
 * Direct-stream adapter for one addon the user has explicitly configured and authorised.
 *
 * Only HTTPS `url` stream objects are accepted. Protocol fields that identify peer-to-peer or
 * indirect transports are deliberately not modelled, so they cannot reach playback.
 */
class StremioAddonSourceRepository(
    private val settingsRepository: SettingsRepository,
    private val urlValidator: UrlValidator,
    private val httpClient: OkHttpClient,
    private val json: kotlinx.serialization.json.Json,
) : AuthorisedSourceRepository {

    override val providerName: String = "Your configured addon"

    override suspend fun isConfigured(): Boolean {
        val config = settingsRepository.current().stremioAddon
        return config.authorisedByUser && !config.baseUrl.isNullOrBlank()
    }

    override suspend fun findSources(request: PlaybackRequest): AppResult<List<PlayableSource>> {
        if (request is PlaybackRequest.LiveChannel) return AppResult.Success(emptyList())
        val config = settingsRepository.current().stremioAddon
        if (!config.authorisedByUser || config.baseUrl.isNullOrBlank()) {
            return AppResult.Success(emptyList())
        }

        val baseUrl = validatedBaseUrl(config.baseUrl) ?: return AppResult.Success(emptyList())
        return try {
            val api = api(baseUrl)
            val manifest = api.manifest()
            val type = streamType(request, manifest) ?: return AppResult.Success(emptyList())
            val id = requestId(request, manifest) ?: return AppResult.Success(emptyList())
            val authorisation = Authorisation.UserAddon(
                addonId = manifest.id,
                addonName = config.displayName?.takeIf { it.isNotBlank() } ?: manifest.name,
            )
            val sources = api.streams(type, id).streams.mapIndexedNotNull { index, stream ->
                val url = stream.url?.trim().orEmpty()
                if (url.isBlank() || stream.behaviorHints?.notWebReady == true || !url.startsWith("https://")) {
                    return@mapIndexedNotNull null
                }
                val validated = urlValidator.validate(url, UrlValidator.Usage.APP_SERVICE)
                if (validated is AppResult.Failure) return@mapIndexedNotNull null
                PlayableSource(
                    id = "stremio:" + manifest.id + ":" + index,
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
        } catch (error: IOException) {
            AppResult.Failure(AppError.Network("The configured addon could not be reached."))
        } catch (error: Exception) {
            AppResult.Failure(AppError.ParseFailed("The configured addon returned an invalid response."))
        }
    }

    override suspend fun resolve(source: PlayableSource): AppResult<ResolvedStream> {
        val authorisation = source.authorisation as? Authorisation.UserAddon
            ?: return mismatch()
        val config = settingsRepository.current().stremioAddon
        if (!config.authorisedByUser || config.baseUrl.isNullOrBlank()) return mismatch()
        val url = source.resolutionKey
        if (!url.startsWith("https://")) return AppResult.Failure(AppError.SchemeRejected("http", "Only encrypted addon streams are supported."))
        val validated = urlValidator.validate(url, UrlValidator.Usage.APP_SERVICE)
        if (validated is AppResult.Failure) return validated
        return try {
            httpClient.newCall(Request.Builder().url(url).header("Range", "bytes=0-2047").build())
                .execute().use { response ->
                    if (!response.isSuccessful && response.code != 416) throw IOException("HTTP " + response.code)
                }
            AppResult.Success(ResolvedStream(source, url))
        } catch (error: IOException) {
            AppResult.Failure(AppError.Network("The stream from " + authorisation.displayName + " is unavailable."))
        }
    }

    private fun api(baseUrl: String): StremioAddonApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(httpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(StremioAddonApi::class.java)

    /**
     * Shares [normaliseAddonBaseUrl] with the setup probe, so what is verified on the settings
     * screen is byte-for-byte what is requested at playback. Two separate normalisers would drift,
     * and the failure would be an addon that tests fine and then returns nothing.
     */
    private fun validatedBaseUrl(raw: String): String? {
        val normalised = normaliseAddonBaseUrl(raw) ?: return null
        val validated = urlValidator.validate(normalised, UrlValidator.Usage.APP_SERVICE)
        return if (validated is AppResult.Success) normalised else null
    }

    private fun streamType(request: PlaybackRequest, manifest: StremioManifestDto): String? {
        if (!manifest.resources.any(::isStreamResource)) return null
        return when (request) {
            is PlaybackRequest.Movie -> "movie".takeIf { manifest.types.isEmpty() || it in manifest.types }
            is PlaybackRequest.TvEpisode -> "series".takeIf { manifest.types.isEmpty() || it in manifest.types }
            is PlaybackRequest.LiveChannel -> null
        }
    }

    private fun requestId(request: PlaybackRequest, manifest: StremioManifestDto): String? {
        val id = when (request) {
            is PlaybackRequest.Movie -> request.id.value
            is PlaybackRequest.TvEpisode -> request.ref.showId.value + ":" + request.ref.seasonNumber + ":" + request.ref.episodeNumber
            is PlaybackRequest.LiveChannel -> return null
        }
        return id.takeIf { manifest.idPrefixes.isEmpty() || manifest.idPrefixes.any(id::startsWith) }
    }

    private fun isStreamResource(element: kotlinx.serialization.json.JsonElement): Boolean = when (element) {
        is JsonPrimitive -> element.contentOrNull == "stream"
        is JsonObject -> element["name"]?.jsonPrimitive?.contentOrNull == "stream"
        else -> false
    }

    private fun containerFor(url: String): StreamContainer = when {
        url.contains(".m3u8", true) -> StreamContainer.HLS
        url.contains(".mpd", true) -> StreamContainer.DASH
        else -> StreamContainer.PROGRESSIVE
    }

    private fun mismatch(): AppResult<ResolvedStream> = AppResult.Failure(
        AppError.Unauthorised("That source did not come from your configured addon.", false),
    )
}
