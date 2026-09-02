package com.sustream.tv.presentation.navigation

import com.sustream.tv.domain.model.MediaId
import com.sustream.tv.domain.model.MediaType

/**
 * Navigation routes.
 *
 * String routes with explicit builders rather than Navigation's type-safe serialisable routes: the
 * arguments here are small and already string-shaped ([MediaId] is a value class over a string),
 * and a builder function per destination gives one place where encoding happens. Every argument is
 * URL-encoded on the way in and decoded on the way out, which matters because a channel id contains
 * a colon and a playlist id is a UUID.
 */
object Routes {

    // ---- Top-level sections (the nav rail) ----------------------------------
    const val HOME = "home"
    const val FILMS = "films"
    const val TV = "tv"
    const val LIVE = "live"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"

    /** Sections reachable from the rail, in rail order. */
    val SECTIONS = listOf(HOME, FILMS, TV, LIVE, SEARCH, LIBRARY, SETTINGS)

    // ---- Startup ------------------------------------------------------------
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val AUTH = "auth"

    // ---- Details ------------------------------------------------------------
    private const val DETAILS_BASE = "details"
    const val ARG_MEDIA_ID = "mediaId"
    const val DETAILS = "$DETAILS_BASE/{$ARG_MEDIA_ID}"

    fun details(id: MediaId): String = DETAILS_BASE + "/" + encode(id.value)

    // ---- Player -------------------------------------------------------------
    private const val PLAYER_BASE = "player"
    const val ARG_PLAYBACK_KIND = "kind"
    const val ARG_TARGET_ID = "targetId"
    const val ARG_SEASON = "season"
    const val ARG_EPISODE = "episode"
    const val PLAYER =
        "$PLAYER_BASE/{$ARG_PLAYBACK_KIND}/{$ARG_TARGET_ID}?$ARG_SEASON={$ARG_SEASON}&$ARG_EPISODE={$ARG_EPISODE}"

    const val KIND_MOVIE = "movie"
    const val KIND_EPISODE = "episode"
    const val KIND_CHANNEL = "channel"

    fun playerForMovie(id: MediaId): String =
        PLAYER_BASE + "/" + KIND_MOVIE + "/" + encode(id.value) + "?" + ARG_SEASON + "=-1&" +
            ARG_EPISODE + "=-1"

    fun playerForEpisode(showId: MediaId, season: Int, episode: Int): String =
        PLAYER_BASE + "/" + KIND_EPISODE + "/" + encode(showId.value) + "?" + ARG_SEASON + "=" +
            season + "&" + ARG_EPISODE + "=" + episode

    fun playerForChannel(channelId: String): String =
        PLAYER_BASE + "/" + KIND_CHANNEL + "/" + encode(channelId) + "?" + ARG_SEASON + "=-1&" +
            ARG_EPISODE + "=-1"

    // ---- Catalogue browse ---------------------------------------------------
    fun catalogue(type: MediaType): String = if (type == MediaType.MOVIE) FILMS else TV

    // ---- Settings sub-screens ----------------------------------------------
    const val SETTINGS_ACCOUNT = "settings/account"
    const val SETTINGS_PLAYBACK = "settings/playback"
    const val SETTINGS_SUBTITLES = "settings/subtitles"
    const val SETTINGS_PROVIDERS = "settings/providers"
    const val SETTINGS_IPTV = "settings/iptv"
    const val SETTINGS_DIAGNOSTICS = "settings/diagnostics"
    const val SETTINGS_ABOUT = "settings/about"

    // ---- Live TV sub-screens -----------------------------------------------
    private const val PLAYLIST_BASE = "live/playlist"
    const val ARG_PLAYLIST_ID = "playlistId"
    const val PLAYLIST_DETAIL = "$PLAYLIST_BASE/{$ARG_PLAYLIST_ID}"

    fun playlistDetail(playlistId: String): String = PLAYLIST_BASE + "/" + encode(playlistId)

    // ---- Encoding -----------------------------------------------------------

    /**
     * Encodes an argument for a route.
     *
     * `URLEncoder` turns a space into `+`, which a path segment reads literally, so it is corrected
     * to `%20`. Slashes must also go, or a channel id containing one would split into two segments.
     */
    fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, Charsets.UTF_8.name()) }
            .getOrDefault(value)
}
