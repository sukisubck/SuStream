package com.sustream.tv.provider.htmljson

import com.sustream.tv.core.net.UrlValidator
import com.sustream.tv.core.net.safeApiCall
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.core.util.DispatcherProvider
import com.sustream.tv.domain.model.AddonTestResult
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Fetches and inspects an addon's manifest before its URL is saved.
 *
 * The probe answers the only question that matters at setup: does this address advertise the
 * `stream` resource? An addon that only supplies catalogues or metadata cannot be used here and
 * it is far better to say so at setup than to silently return nothing during playback.
 */
class AddonManifestProbe(
    private val httpClient: OkHttpClient,
    private val urlValidator: UrlValidator,
    private val json: Json,
    private val dispatchers: DispatcherProvider,
) {

    suspend fun probe(rawUrl: String): AppResult<AddonTestResult> = withContext(dispatchers.io) {
        val normalised = normaliseAddonBaseUrl(rawUrl)
        if (normalised.isNullOrBlank()) {
            return@withContext AppResult.Failure(
                AppError.SchemeRejected(null, "Enter the addon's address."),
            )
        }

        val validated = urlValidator.validate(normalised, UrlValidator.Usage.APP_SERVICE)
        if (validated is AppResult.Failure) return@withContext validated

        val result = safeApiCall {
            Retrofit.Builder()
                .baseUrl(normalised)
                .client(httpClient)
                .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE.toMediaType()))
                .build()
                .create(HtmlJsonAddonApi::class.java)
                .manifest()
        }

        when (result) {
            is AppResult.Failure -> AppResult.Failure(describe(result.error))

            is AppResult.Success -> {
                val manifest = result.value
                if (!manifest.resources.any(::advertisesStreamResource)) {
                    return@withContext AppResult.Failure(
                        AppError.UnsupportedFormat(
                            "That addon works, but it does not provide streams — so it cannot " +
                                    "supply anything to play.",
                        ),
                    )
                }

                AppResult.Success(
                    AddonTestResult(
                        addonId = manifest.id,
                        addonName = manifest.name.ifBlank { manifest.id },
                        types = manifest.types,
                        supportsStreams = true,
                        normalisedBaseUrl = normalised,
                    ),
                )
            }
        }
    }

    private fun describe(error: AppError): AppError = when (error) {
        is AppError.Network ->
            AppError.Network("Could not reach that address. Check the URL and that the addon is running.")
        is AppError.Timeout ->
            AppError.Timeout("That address did not respond in time.")
        is AppError.ParseFailed ->
            AppError.ParseFailed("That address answered, but not with an addon manifest.")
        is AppError.NotFound ->
            AppError.NotFound("No manifest was found there. Check the URL.")
        else -> error
    }

    private fun advertisesStreamResource(element: JsonElement): Boolean = when (element) {
        is JsonPrimitive -> element.contentOrNull == STREAM_RESOURCE
        is JsonObject    -> element[RESOURCE_NAME_KEY]?.jsonPrimitive?.contentOrNull == STREAM_RESOURCE
        else             -> false
    }

    private companion object {
        const val JSON_MEDIA_TYPE = "application/json"
        const val STREAM_RESOURCE = "stream"
        const val RESOURCE_NAME_KEY = "name"
    }
}

/**
 * Normalises whatever the user typed into a Retrofit base URL.
 * Accepts: `https://host/`, `https://host`, `https://host/manifest.json`,
 * `https://host/config/manifest.json`. Returns null for empty input.
 */
fun normaliseAddonBaseUrl(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val withoutManifest = trimmed.removeSuffix("manifest.json")
    val withoutQuery = withoutManifest.substringBefore('?').substringBefore('#')
    if (withoutQuery.isEmpty()) return null
    return if (withoutQuery.endsWith('/')) withoutQuery else "$withoutQuery/"
}