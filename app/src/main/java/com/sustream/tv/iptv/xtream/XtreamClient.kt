package com.sustream.tv.iptv.xtream

import com.sustream.tv.core.log.AppLog
import com.sustream.tv.core.log.Redact
import com.sustream.tv.core.log.Secret
import com.sustream.tv.core.net.UrlValidator
import com.sustream.tv.core.net.safeApiCall
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.iptv.m3u.M3uEntry
import com.sustream.tv.iptv.m3u.M3uParser
import com.sustream.tv.iptv.m3u.M3uPlaylist
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

private const val TAG = "Xtream"

/**
 * Client for an Xtream-style IPTV provider the user subscribes to.
 *
 * Two acquisition strategies, tried in order, because provider support varies:
 *
 *  1. **`player_api.php`** — structured JSON, which gives categories and channel ids cleanly.
 *  2. **`get.php` M3U export** — every such provider supports this, so it is the fallback when the
 *     JSON API is absent, disabled, or returns something unparseable.
 *
 * Falling back rather than failing matters because a user whose provider only exposes the M3U
 * export would otherwise be told their (perfectly valid) credentials are wrong.
 *
 * Credentials are handled as [Secret] and every logged URL goes through [Redact], which collapses
 * the `username`/`password` **path segments** that Xtream stream URLs embed — a query-parameter
 * filter alone would miss them entirely.
 */
class XtreamClient(
    private val httpClient: OkHttpClient,
    private val urlValidator: UrlValidator,
    private val config: XtreamEndpointConfig = XtreamEndpointConfig(),
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Verifies credentials and returns the account summary.
     *
     * Called when a playlist is added, so a wrong password produces "sign-in rejected by the
     * provider" immediately rather than an empty channel list ten seconds later.
     */
    suspend fun authenticate(
        serverUrl: String,
        username: String,
        password: Secret,
        cleartextAcknowledged: Boolean,
        allowPrivateHosts: Boolean,
    ): AppResult<XtreamAccount> {
        val url = config.playerApiUrl(serverUrl, username, password.reveal())
        val validated = urlValidator.validate(
            raw = url,
            usage = UrlValidator.Usage.USER_MEDIA,
            options = UrlValidator.Options(
                cleartextAcknowledged = cleartextAcknowledged,
                allowPrivateHosts = allowPrivateHosts,
            ),
        )
        if (validated is AppResult.Failure) return validated

        return safeApiCall {
            val body = get(url)
            val response = json.decodeFromString<XtreamAuthResponse>(body)

            // Providers signal a bad password with `auth: 0` and HTTP 200, so the status code alone
            // is not enough to tell success from failure.
            if (response.userInfo?.auth != 1) {
                throw XtreamAuthException(
                    response.userInfo?.status ?: "The provider rejected these details.",
                )
            }
            XtreamAccount(
                username = response.userInfo.username.orEmpty(),
                status = response.userInfo.status.orEmpty(),
                expiryEpochSeconds = response.userInfo.expDate?.asLongOrNull(),
                maxConnections = response.userInfo.maxConnections?.asLongOrNull()?.toInt(),
                activeConnections = response.userInfo.activeConnections?.asLongOrNull()?.toInt(),
                serverUrl = config.normaliseServer(serverUrl),
            )
        }.mapAuthFailure()
    }

    /**
     * Fetches the channel list.
     *
     * Returns [M3uEntry] values regardless of which strategy succeeded, so the repository has one
     * code path from here on and does not care how the list was obtained.
     */
    suspend fun fetchChannels(
        serverUrl: String,
        username: String,
        password: Secret,
        cleartextAcknowledged: Boolean,
        allowPrivateHosts: Boolean,
    ): AppResult<XtreamChannelResult> {
        val viaApi = fetchChannelsViaPlayerApi(
            serverUrl, username, password, cleartextAcknowledged, allowPrivateHosts,
        )
        if (viaApi is AppResult.Success && viaApi.value.entries.isNotEmpty()) return viaApi

        AppLog.i(TAG, "player_api returned nothing usable; falling back to the M3U export")

        return fetchChannelsViaExport(
            serverUrl, username, password, cleartextAcknowledged, allowPrivateHosts,
        )
    }

    private suspend fun fetchChannelsViaPlayerApi(
        serverUrl: String,
        username: String,
        password: Secret,
        cleartextAcknowledged: Boolean,
        allowPrivateHosts: Boolean,
    ): AppResult<XtreamChannelResult> {
        val categoriesUrl = config.playerApiUrl(
            serverUrl, username, password.reveal(), config.actionLiveCategories,
        )
        val streamsUrl = config.playerApiUrl(
            serverUrl, username, password.reveal(), config.actionLiveStreams,
        )

        val options = UrlValidator.Options(cleartextAcknowledged, allowPrivateHosts)
        val check = urlValidator.validate(streamsUrl, UrlValidator.Usage.USER_MEDIA, options)
        if (check is AppResult.Failure) return check

        return safeApiCall {
            // Category names are a separate call; without them channels would be grouped by opaque
            // numeric ids, which is unusable as a category filter.
            val categoryNames = runCatching {
                json.decodeFromString<List<XtreamCategory>>(get(categoriesUrl))
                    .associate { it.categoryId.orEmpty() to it.categoryName.orEmpty() }
            }.getOrElse {
                AppLog.w(TAG, "Live categories unavailable; channels will be ungrouped")
                emptyMap()
            }

            val streams = json.decodeFromString<List<XtreamLiveStream>>(get(streamsUrl))

            val entries = streams.mapIndexedNotNull { index, stream ->
                val streamId = stream.streamId?.asLongOrNull()?.toString() ?: return@mapIndexedNotNull null
                val name = stream.name?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                M3uEntry(
                    displayName = name,
                    streamUrl = config.liveStreamUrl(
                        serverUrl = serverUrl,
                        username = username,
                        password = password.reveal(),
                        streamId = streamId,
                    ),
                    tvgId = stream.epgChannelId?.takeIf { it.isNotBlank() },
                    tvgName = name,
                    logoUrl = stream.streamIcon?.takeIf { it.isNotBlank() },
                    group = categoryNames[stream.categoryId?.asStringOrNull()]
                        ?.takeIf { it.isNotBlank() },
                    channelNumber = stream.num?.asLongOrNull()?.toString(),
                    sourceLineNumber = index + 1,
                )
            }

            XtreamChannelResult(entries = entries, epgUrl = null, viaExport = false)
        }.mapAuthFailure()
    }

    private suspend fun fetchChannelsViaExport(
        serverUrl: String,
        username: String,
        password: Secret,
        cleartextAcknowledged: Boolean,
        allowPrivateHosts: Boolean,
    ): AppResult<XtreamChannelResult> {
        val url = config.playlistExportUrl(serverUrl, username, password.reveal())
        val options = UrlValidator.Options(cleartextAcknowledged, allowPrivateHosts)
        val check = urlValidator.validate(url, UrlValidator.Usage.USER_MEDIA, options)
        if (check is AppResult.Failure) return check

        return safeApiCall {
            val body = get(url)
            if (!M3uParser.looksLikePlaylist(body)) {
                throw XtreamAuthException(
                    "The provider returned a page rather than a playlist. The subscription may " +
                        "have lapsed.",
                )
            }
            val playlist: M3uPlaylist = M3uParser.parse(body)
            XtreamChannelResult(
                entries = playlist.entries,
                epgUrl = playlist.epgUrl,
                viaExport = true,
            )
        }.mapAuthFailure()
    }

    /** The provider's own XMLTV endpoint, or null when it does not offer one. */
    fun epgUrlFor(serverUrl: String, username: String, password: Secret): String =
        config.epgUrl(serverUrl, username, password.reveal())

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP " + response.code + " from " + Redact.url(url))
            }
            return response.body.string()
        }
    }

    /**
     * Maps a rejected sign-in onto [AppError.Unauthorised].
     *
     * Without this, an expired subscription reads as a generic network error and the user has no
     * idea their provider is the problem rather than their Wi-Fi.
     *
     * The marker is carried in the message because [XtreamAuthException] is an `IOException` — that
     * is what lets it travel through `safeApiCall` — and `safeApiCall` therefore classifies it as
     * [AppError.Network]. Matching on the marker rather than on the error type is what recovers the
     * real meaning.
     */
    private fun <T> AppResult<T>.mapAuthFailure(): AppResult<T> {
        if (this !is AppResult.Failure) return this
        val detail = error.detail ?: return this
        if (!detail.contains(AUTH_MARKER)) return this
        return AppResult.Failure(
            AppError.Unauthorised(
                detail = detail.substringAfter(AUTH_MARKER).ifBlank {
                    "The provider rejected these details."
                },
                refreshable = false,
            ),
        )
    }

    private companion object {
        const val AUTH_MARKER = "XTREAM_AUTH: "
    }

    /** Carries a provider-side rejection through `safeApiCall` so it can be re-typed above. */
    private class XtreamAuthException(detail: String) :
        IOException(AUTH_MARKER + detail)
}

data class XtreamAccount(
    val username: String,
    val status: String,
    val expiryEpochSeconds: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val serverUrl: String,
)

data class XtreamChannelResult(
    val entries: List<M3uEntry>,
    /** Present only when the M3U export supplied an `x-tvg-url`. */
    val epgUrl: String?,
    /** True when the M3U fallback was used; surfaced in diagnostics. */
    val viaExport: Boolean,
)

// ---- Wire types ---------------------------------------------------------------
//
// Every field is nullable, and numeric fields are typed as JsonPrimitive rather than Int or Long.
// This is not defensiveness for its own sake: Xtream panels are notoriously inconsistent about
// whether `stream_id`, `exp_date` and `num` are JSON numbers or JSON strings, and the same provider
// can vary between endpoints. A typed Int would fail the whole parse on one quoted number.
// TODO(xtream-contract): tighten these once the client's provider is confirmed.

@Serializable
internal data class XtreamAuthResponse(
    @SerialName("user_info") val userInfo: XtreamUserInfo? = null,
    @SerialName("server_info") val serverInfo: XtreamServerInfo? = null,
)

@Serializable
internal data class XtreamUserInfo(
    val username: String? = null,
    /** `1` for success. Providers return HTTP 200 with `auth: 0` for a bad password. */
    val auth: Int? = null,
    val status: String? = null,
    @SerialName("exp_date") val expDate: JsonPrimitive? = null,
    @SerialName("max_connections") val maxConnections: JsonPrimitive? = null,
    @SerialName("active_cons") val activeConnections: JsonPrimitive? = null,
)

@Serializable
internal data class XtreamServerInfo(
    val url: String? = null,
    val port: JsonPrimitive? = null,
    @SerialName("https_port") val httpsPort: JsonPrimitive? = null,
    @SerialName("server_protocol") val serverProtocol: String? = null,
)

@Serializable
internal data class XtreamCategory(
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("category_name") val categoryName: String? = null,
)

@Serializable
internal data class XtreamLiveStream(
    val num: JsonPrimitive? = null,
    val name: String? = null,
    @SerialName("stream_id") val streamId: JsonPrimitive? = null,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    @SerialName("category_id") val categoryId: JsonPrimitive? = null,
)

/** Reads a value that may be a JSON number or a quoted number. */
internal fun JsonPrimitive.asLongOrNull(): Long? = content.trim().toLongOrNull()

internal fun JsonPrimitive.asStringOrNull(): String? = content.trim().takeIf { it.isNotEmpty() }
