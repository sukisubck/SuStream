package com.sustream.tv.iptv.xtream

/**
 * Every path and parameter name the Xtream-style client uses, in one place.
 *
 * ## Why this file exists
 *
 * The workbook's requirement (sheet `Features`, row 37) is *"Support the client's specified X
 * playlist format/provider"* — with no field names, no base path and no auth scheme given. The
 * brief then says to implement it *"without assuming undocumented field names"*.
 *
 * Those two requirements can only be reconciled one way: implement against the shape that is
 * actually widely documented and observed (the `player_api.php` / `get.php` convention that the
 * supplied prototype also assumes, with `xtreamServer` / `xtreamUser` / `xtreamPass` and a server
 * of `http://iptv-pro.stream:8080`), and put **every** assumption in a single overridable object so
 * that adapting to the client's real provider is one file, not a hunt through the codebase.
 *
 * Nothing outside this file hard-codes an Xtream path or parameter name.
 *
 * TODO(xtream-contract): confirm against the client's provider documentation. Each field below is
 *  annotated with how confident we are in it.
 */
data class XtreamEndpointConfig(
    /** Confirmed by the prototype and universal across providers. */
    val playerApiPath: String = DEFAULT_PLAYER_API_PATH,

    /**
     * The M3U export endpoint. Used as a fallback when `player_api.php` is unavailable — some
     * providers expose only this.
     */
    val playlistExportPath: String = DEFAULT_PLAYLIST_PATH,

    /** XMLTV guide endpoint. Only called if the provider advertises or accepts it. */
    val epgPath: String = DEFAULT_EPG_PATH,

    // ---- Query parameter names ---------------------------------------------
    val usernameParam: String = "username",
    val passwordParam: String = "password",
    val actionParam: String = "action",
    val categoryIdParam: String = "category_id",

    // ---- Action values ------------------------------------------------------
    /** Live TV categories. */
    val actionLiveCategories: String = "get_live_categories",
    /** Live channels, optionally filtered by category. */
    val actionLiveStreams: String = "get_live_streams",
    /** Short EPG for one channel. Not used by default; the XMLTV feed is preferred. */
    val actionShortEpg: String = "get_short_epg",

    // ---- Stream URL construction -------------------------------------------
    /**
     * Live stream path template.
     *
     * The universal convention is `/live/<username>/<password>/<streamId>.<ext>`. Note that this
     * embeds credentials in the **path**, not the query string, which is exactly why
     * `core.log.Redact.url` collapses these segments before anything is logged.
     */
    val liveStreamPathTemplate: String = DEFAULT_LIVE_TEMPLATE,

    /**
     * Container extension for live streams.
     *
     * `m3u8` is preferred over `ts`: HLS lets ExoPlayer adapt and recover from a dropped segment,
     * whereas a raw MPEG-TS stream cannot be recovered from without restarting.
     */
    val liveStreamExtension: String = "m3u8",

    /** Fallback extension when the provider does not serve HLS. */
    val liveStreamFallbackExtension: String = "ts",

    /** Playlist export type, for the [playlistExportPath] fallback. */
    val playlistType: String = "m3u_plus",
    val playlistOutput: String = "m3u8",
) {

    /** `https://host:8080/player_api.php?username=…&password=…&action=…` */
    fun playerApiUrl(
        serverUrl: String,
        username: String,
        password: String,
        action: String? = null,
        categoryId: String? = null,
    ): String = buildString {
        append(normaliseServer(serverUrl))
        append(playerApiPath)
        append('?').append(usernameParam).append('=').append(encode(username))
        append('&').append(passwordParam).append('=').append(encode(password))
        if (action != null) append('&').append(actionParam).append('=').append(encode(action))
        if (categoryId != null) {
            append('&').append(categoryIdParam).append('=').append(encode(categoryId))
        }
    }

    /** `https://host:8080/get.php?username=…&password=…&type=m3u_plus&output=m3u8` */
    fun playlistExportUrl(
        serverUrl: String,
        username: String,
        password: String,
    ): String = buildString {
        append(normaliseServer(serverUrl))
        append(playlistExportPath)
        append('?').append(usernameParam).append('=').append(encode(username))
        append('&').append(passwordParam).append('=').append(encode(password))
        append("&type=").append(encode(playlistType))
        append("&output=").append(encode(playlistOutput))
    }

    /** `https://host:8080/xmltv.php?username=…&password=…` */
    fun epgUrl(
        serverUrl: String,
        username: String,
        password: String,
    ): String = buildString {
        append(normaliseServer(serverUrl))
        append(epgPath)
        append('?').append(usernameParam).append('=').append(encode(username))
        append('&').append(passwordParam).append('=').append(encode(password))
    }

    /** `https://host:8080/live/<user>/<pass>/<id>.m3u8` */
    fun liveStreamUrl(
        serverUrl: String,
        username: String,
        password: String,
        streamId: String,
        extension: String = liveStreamExtension,
    ): String = normaliseServer(serverUrl) + liveStreamPathTemplate
        .replace(PLACEHOLDER_USERNAME, encodePathSegment(username))
        .replace(PLACEHOLDER_PASSWORD, encodePathSegment(password))
        .replace(PLACEHOLDER_STREAM_ID, encodePathSegment(streamId))
        .replace(PLACEHOLDER_EXTENSION, extension)

    /**
     * Trims a trailing slash and any path the user pasted.
     *
     * Users paste all of `http://host:8080`, `http://host:8080/`, and
     * `http://host:8080/player_api.php?username=…`. Normalising here means the form accepts all
     * three rather than failing on two of them.
     */
    internal fun normaliseServer(serverUrl: String): String {
        val trimmed = serverUrl.trim()
        val withoutQuery = trimmed.substringBefore('?')
        val withoutKnownPaths = KNOWN_PATHS.fold(withoutQuery) { acc, path ->
            if (acc.endsWith(path, ignoreCase = true)) acc.dropLast(path.length) else acc
        }
        return withoutKnownPaths.trimEnd('/')
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    /**
     * Path segments are encoded differently from query values: `+` means a literal plus in a path
     * but a space in a query, so `URLEncoder` alone would corrupt a password containing `+`.
     */
    private fun encodePathSegment(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    companion object {
        const val DEFAULT_PLAYER_API_PATH = "/player_api.php"
        const val DEFAULT_PLAYLIST_PATH = "/get.php"
        const val DEFAULT_EPG_PATH = "/xmltv.php"
        const val DEFAULT_LIVE_TEMPLATE = "/live/{username}/{password}/{streamId}.{extension}"

        private const val PLACEHOLDER_USERNAME = "{username}"
        private const val PLACEHOLDER_PASSWORD = "{password}"
        private const val PLACEHOLDER_STREAM_ID = "{streamId}"
        private const val PLACEHOLDER_EXTENSION = "{extension}"

        private val KNOWN_PATHS = listOf(
            DEFAULT_PLAYER_API_PATH,
            DEFAULT_PLAYLIST_PATH,
            DEFAULT_EPG_PATH,
        )
    }
}
