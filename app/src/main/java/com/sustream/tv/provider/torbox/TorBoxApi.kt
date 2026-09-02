package com.sustream.tv.provider.torbox

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * TorBox API surface, scoped to the user's **own** cloud account.
 *
 * ## Scope, and why it is this narrow
 *
 * Three operations only:
 *  1. read the account (is it valid, when does it expire, what is the quota);
 *  2. list what the user already has in their account;
 *  3. request a download link for a file the user already owns.
 *
 * Deliberately absent, and not to be added:
 *  * any search endpoint;
 *  * adding a torrent, magnet or hash;
 *  * checking whether some hash is "cached".
 *
 * Those are the operations that turn a debrid client into an infringement tool, and their absence
 * is a design decision rather than an oversight. See docs/IMPLEMENTATION_PLAN.md section 1.2 and
 * docs/DEFERRED_AND_RESTRICTED.md.
 *
 * ## Contract confidence
 *
 * TODO(torbox-contract): the paths and field names below follow TorBox's published v1 API shape and
 *  must be confirmed against their current official documentation before release. Everything is
 *  written to fail soft rather than to guess:
 *   * every response field is nullable and every unknown key is ignored, so a renamed or added
 *     field degrades one value instead of failing the request;
 *   * numeric fields are [JsonPrimitive] because the API is not consistent about quoting numbers;
 *   * `data` is [JsonElement] on the envelope, because the same wrapper carries an object, an array
 *     or a string depending on the endpoint.
 *  Nothing in the app depends on a field whose meaning has not been confirmed: see how
 *  [TorBoxRepositoryImpl] treats an absent quota as "unknown" rather than as unlimited.
 */
internal interface TorBoxApi {

    /** Account status. Used by connect, by Settings, and by Diagnostics. */
    @GET("user/me")
    suspend fun me(
        /** Some deployments require this to include the settings block; harmless when ignored. */
        @Query("settings") includeSettings: Boolean = false,
    ): Response<TorBoxEnvelope>

    /**
     * The items already in the user's account.
     *
     * `bypass_cache` is exposed because a stale list is a real annoyance right after the user adds
     * something through TorBox's own interface and then opens SuStream.
     */
    @GET("torrents/mylist")
    suspend fun myList(
        @Query("bypass_cache") bypassCache: Boolean = false,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = DEFAULT_PAGE_SIZE,
    ): Response<TorBoxEnvelope>

    /**
     * Requests a time-limited download link for a file the user owns.
     *
     * The returned link is short-lived, which is why [com.sustream.tv.domain.model.ResolvedStream]
     * carries an expiry and the player re-resolves rather than failing on a dead URL after the app
     * has been backgrounded.
     */
    @GET("torrents/requestdl")
    suspend fun requestDownloadLink(
        @Query("token") token: String,
        @Query("torrent_id") itemId: String,
        @Query("file_id") fileId: String,
    ): Response<TorBoxEnvelope>

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}

/**
 * The common response wrapper.
 *
 * `data` is left as a raw [JsonElement] and decoded per call site, because the same envelope
 * carries an object (`user/me`), an array (`torrents/mylist`) and a bare string (the download
 * link). Typing it as one shape would break two of the three.
 */
@Serializable
internal data class TorBoxEnvelope(
    val success: Boolean = false,
    val detail: String? = null,
    val error: String? = null,
    val data: JsonElement? = null,
)

@Serializable
internal data class TorBoxUser(
    val id: JsonPrimitive? = null,
    val email: String? = null,
    val plan: JsonPrimitive? = null,
    @SerialName("premium_expires_at") val premiumExpiresAt: String? = null,
    @SerialName("total_downloaded") val totalDownloaded: JsonPrimitive? = null,
    /** Absent on some plans. Treated as "unknown", never as unlimited. */
    @SerialName("customer") val customer: String? = null,
    @SerialName("is_subscribed") val isSubscribed: Boolean? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
internal data class TorBoxItem(
    val id: JsonPrimitive? = null,
    val name: String? = null,
    val hash: String? = null,
    val size: JsonPrimitive? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("download_finished") val downloadFinished: Boolean? = null,
    @SerialName("download_present") val downloadPresent: Boolean? = null,
    val files: List<TorBoxFile> = emptyList(),
)

@Serializable
internal data class TorBoxFile(
    val id: JsonPrimitive? = null,
    val name: String? = null,
    @SerialName("short_name") val shortName: String? = null,
    val size: JsonPrimitive? = null,
    @SerialName("mimetype") val mimeType: String? = null,
)
