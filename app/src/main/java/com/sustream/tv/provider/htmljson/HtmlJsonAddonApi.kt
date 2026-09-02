package com.sustream.tv.provider.htmljson

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.GET
import retrofit2.http.Path

internal interface HtmlJsonAddonApi {
    @GET("manifest.json")
    suspend fun manifest(): HtmlJsonManifestDto

    @GET("stream/{type}/{id}.json")
    suspend fun streams(
        @Path("type") type: String,
        @Path("id") id: String,
    ): HtmlJsonStreamsDto
}

@Serializable
internal data class HtmlJsonManifestDto(
    val id: String,
    val name: String,
    val resources: List<JsonElement> = emptyList(),
    val types: List<String> = emptyList(),
    @SerialName("idPrefixes") val idPrefixes: List<String> = emptyList(),
)

@Serializable
internal data class HtmlJsonStreamsDto(
    val streams: List<HtmlJsonStreamDto> = emptyList(),
)

@Serializable
internal data class HtmlJsonStreamDto(
    val url: String? = null,
    val name: String? = null,
    val title: String? = null,
    @SerialName("behaviorHints") val behaviorHints: HtmlJsonStreamHintsDto? = null,
)

@Serializable
internal data class HtmlJsonStreamHintsDto(
    val filename: String? = null,
    @SerialName("notWebReady") val notWebReady: Boolean = false,
)