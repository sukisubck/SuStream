package com.sustream.tv.provider.stremio

import com.sustream.tv.core.net.UrlValidator
import com.sustream.tv.core.net.safeApiCall
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.core.util.DispatcherProvider
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
 * Fetches and inspects an addon's manifest, so a URL can be verified **before** it is saved.
 *
 * Without this, a typo or a dead host is not discovered until the user opens a title and gets an
 * empty availability panel with no explanation. Probing at setup turns that into an immediate,
 * specific failure on the screen where it can be fixed.
 *
 * It also answers the only question that matters about an addon before accepting it: does it
 * actually advertise the `stream` resource? An addon that only supplies catalogues or metadata is
 * perfectly valid and completely useless to this app, and saying so at setup is better than
 * appearing to work and never returning a source.
 */
class AddonManifestProbe(
    private val httpClient: OkHttpClient,
    private val urlValidator: UrlValidator,
    private val json: Json,
    private val dispatchers: DispatcherProvider,
) {

    /**
     * @param rawUrl whatever the user typed. Accepts the manifest URL or the base, with or without
     *   a trailing slash — see [normaliseAddonBaseUrl].
     */
    suspend fun probe(rawUrl: String): AppResult<AddonDescriptor> = withContext(dispatchers.io) {
        val normalised = normaliseAddonBaseUrl(rawUrl)
        if (normalised.isNullOrBlank()) {
            return@withContext AppResult.Failure(
                AppError.SchemeRejected(null, "Enter the addon's address."),
            )
        }

        // Same validation the adapter applies at request time: HTTPS in release, scheme allowlist,
        // no private or link-local hosts. Failing here means it would have failed there anyway.
        val validated = urlValidator.validate(normalised, UrlValidator.Usage.APP_SERVICE)
        if (validated is AppResult.Failure) return@withContext validated

        val result = safeApiCall {
            Retrofit.Builder()
                .baseUrl(normalised)
                .client(httpClient)
                .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE.toMediaType()))
                .build()
                .create(StremioAddonApi::class.java)
                .manifest()
        }

        when (result) {
            is AppResult.Failure -> AppResult.Failure(describe(result.error))

            is AppResult.Success -> {
                val manifest = result.value
                val supportsStreams = manifest.resources.any(::advertisesStreamResource)

                if (!supportsStreams) {
                    return@withContext AppResult.Failure(
                        AppError.UnsupportedFormat(
                            "That addon works, but it does not provide streams — so it cannot " +
                                "supply anything to play.",
                        ),
                    )
                }

                AppResult.Success(
                    AddonDescriptor(
                        id = manifest.id,
                        name = manifest.name.ifBlank { manifest.id },
                        types = manifest.types,
                        supportsStreams = true,
                        normalisedBaseUrl = normalised,
                    ),
                )
            }
        }
    }

    /**
     * Rewrites transport-level failures into something a user can act on.
     *
     * A bare "could not reach the network" on this screen is unhelpful, because the network is
     * almost always fine and the address is almost always the problem.
     */
    private fun describe(error: AppError): AppError = when (error) {
        is AppError.Network -> AppError.Network(
            "Could not reach that address. Check the URL and that the addon is running.",
        )

        is AppError.Timeout -> AppError.Timeout("That address did not respond in time.")

        is AppError.ParseFailed -> AppError.ParseFailed(
            "That address answered, but not with an addon manifest.",
        )

        is AppError.NotFound -> AppError.NotFound(
            "No manifest was found there. Check the URL.",
        )

        else -> error
    }

    /** `resources` entries are either the bare string `"stream"` or an object with `name`. */
    private fun advertisesStreamResource(element: JsonElement): Boolean = when (element) {
        is JsonPrimitive -> element.contentOrNull == STREAM_RESOURCE
        is JsonObject -> element[RESOURCE_NAME_KEY]?.jsonPrimitive?.contentOrNull == STREAM_RESOURCE
        else -> false
    }

    private companion object {
        const val JSON_MEDIA_TYPE = "application/json"
        const val STREAM_RESOURCE = "stream"
        const val RESOURCE_NAME_KEY = "name"
    }
}

/** What a probed addon turned out to be. Shown back to the user so they can confirm it is theirs. */
data class AddonDescriptor(
    val id: String,
    val name: String,
    /** e.g. `movie`, `series`. Empty means the addon did not restrict itself. */
    val types: List<String>,
    val supportsStreams: Boolean,
    /** The form the app will store and call, which may differ from what was typed. */
    val normalisedBaseUrl: String,
)

/**
 * Normalises whatever the user typed into a Retrofit base URL.
 *
 * People paste all of these, and all four should work:
 *   `https://host/`, `https://host`, `https://host/manifest.json`, `https://host/config/manifest.json`
 *
 * Retrofit requires a trailing slash on a base URL or it silently drops the last path segment, which
 * is a genuinely confusing failure — the request goes to the wrong place and the addon returns a 404
 * that looks like a bad URL rather than a bad join.
 *
 * Returns null for input that cannot be a URL at all. Scheme and host policy is [UrlValidator]'s job,
 * not this function's.
 */
fun normaliseAddonBaseUrl(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null

    val withoutManifest = trimmed.removeSuffix(MANIFEST_FILE)
    val withoutQuery = withoutManifest.substringBefore('?').substringBefore('#')
    if (withoutQuery.isEmpty()) return null

    return if (withoutQuery.endsWith('/')) withoutQuery else withoutQuery + "/"
}

private const val MANIFEST_FILE = "manifest.json"
