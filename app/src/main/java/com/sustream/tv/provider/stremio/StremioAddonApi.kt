package com.sustream.tv.provider.stremio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.GET
import retrofit2.http.Path

internal interface StremioAddonApi {
    @GET("manifest.json")
    suspend fun manifest(): StremioManifestDto

    @GET("stream/{type}/{id}.json")
    suspend fun streams(
        @Path("type") type: String,
        @Path("id") id: String,
    ): StremioStreamsDto
}

@Serializable
internal data class StremioManifestDto(
    val id: String,
    val name: String,
    val resources: List<JsonElement> = emptyList(),
    val types: List<String> = emptyList(),
    @SerialName("idPrefixes") val idPrefixes: List<String> = emptyList(),
)

@Serializable
internal data class StremioStreamsDto(
    val streams: List<StremioStreamDto> = emptyList(),
)

@Serializable
internal data class StremioStreamDto(
    val url: String? = null,
    val name: String? = null,
    val title: String? = null,
    @SerialName("behaviorHints") val behaviorHints: StremioStreamHintsDto? = null,
)

@Serializable
internal data class StremioStreamHintsDto(
    val filename: String? = null,
    @SerialName("notWebReady") val notWebReady: Boolean = false,
)
